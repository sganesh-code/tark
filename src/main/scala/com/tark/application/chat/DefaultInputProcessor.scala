package com.tark.application.chat

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.application.react.DefaultReActExecutor
import com.tark.application.time.Clock
import com.tark.domain.Interaction
import com.tark.domain.context.{Context, Session}
import com.tark.domain.react.ReActState
import com.tark.domain.tool.Tool
import com.tark.ports.inbound.tool.{InputProcessor, SlashCommandRouter}
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.outbound.react.ReActLlmClient.given
import com.tark.ports.outbound.react.{ReActExecutor, ReActLlmClient}
import com.tark.ports.outbound.trace.TraceWriter
import com.tark.ports.shared.react.ReActPrompt
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.shared.tool.{ToolExecutor, ToolRegistry}
import com.tark.ports.shared.ui.{ChatState, Message, ReActToUiMapper}

import java.nio.file.Path

object DefaultInputProcessor {
  given default[F[_]: Sync](using
    sink: Sink[F, Context, Path],
    reactLlm: ReActLlmClient[F],
    slashRouter: SlashCommandRouter[F],
    traceWriter: TraceWriter[F],
    clock: Clock[F],
    reactExecutor: ReActExecutor[DefaultReActExecutor[F], F],
    toolRegistry: ToolRegistry[Context],
    toolExecutor: ToolExecutor[Tool]
  ): InputProcessor[F] with {

    override def process(
      input: String,
      state: ChatState,
      session: Session,
      redraw: ChatState => F[Unit]
    )(using llmClient: LlmClient[F]): F[Option[(ChatState, Session)]] = {
      val trimmedInput = input.trim
      if (trimmedInput.startsWith("/")) {
        slashRouter.process(trimmedInput, state, session, redraw)
      } else {
        val nextState = state.copy(history = state.history :+ Message.User(input), prompt = "", scrollOffset = 0, currentThought = Some(""))
        val systemInstruction = ReActPrompt.systemPrompt(session.context.tools.values.toList)

        val onStepUpdate = (rState: ReActState) => {
          val stepMessages = ReActToUiMapper.toStepMessages(rState.steps)
          val latestThought = rState.steps.lastOption.map(_.thought)
          redraw(nextState.copy(history = nextState.history ++ stepMessages, currentThought = latestThought))
        }

        for {
          _ <- redraw(nextState)

          startedAtMillis <- clock.realTimeMillis
          executor = new DefaultReActExecutor[F](reactLlm, session.context, systemInstruction, maxSteps = 10, onStepUpdate = onStepUpdate)
          reactState <- reactExecutor.execute(executor, input)

          stepMessages = ReActToUiMapper.toStepMessages(reactState.steps)
          isBudgetExceeded = reactState.reasonForStop.exists(r => r == "max_steps_reached" || r == "stagnation_detected")

          finalChatState = if (isBudgetExceeded) {
            nextState.copy(
              history = nextState.history ++ stepMessages :+ Message.System("Error: Maximum tool call execution depth exceeded."),
              currentThought = None
            )
          } else {
            nextState.copy(
              history = nextState.history ++ stepMessages,
              currentThought = None
            )
          }

          _ <- redraw(finalChatState)

          interactionSteps = ReActToUiMapper.toInteractions(reactState.steps, input, startedAtMillis)

          finalInteractions = if (isBudgetExceeded) {
            val errorTimestamp = startedAtMillis + reactState.steps.size + 1L
            interactionSteps :+ Interaction(
              id = s"interaction_$errorTimestamp",
              input = input,
              output = "Error: Maximum tool call execution depth exceeded.",
              timestamp = errorTimestamp,
              toolName = "llm_completion"
            )
          } else {
            interactionSteps
          }

          finalAgentState = ReActToUiMapper.toAgentState(reactState)

          updatedMemory = session.context.memory.copy(
            working = Some(finalAgentState)
          )

          updatedContext = session.context.copy(
            history = session.context.history ++ finalInteractions,
            memory = updatedMemory
          )

          _ <- sink.write(updatedContext, session.sessionPath)
          _ <- traceWriter.writeTrace(reactState, session)
        } yield Some((finalChatState, session.copy(context = updatedContext)))
      }
    }
  }
}
