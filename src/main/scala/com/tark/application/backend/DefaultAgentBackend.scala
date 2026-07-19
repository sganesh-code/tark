package com.tark.application.backend

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.{AgentState, Interaction}
import com.tark.domain.context.{Context, Session}
import com.tark.domain.tool.{OpenAIMessage, OpenAIUsage, ToolCall, ToolResult}
import com.tark.ports.AgentBackend
import com.tark.ports.outbound.backend.*
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.tool.{CommandExecutor, DefaultToolCallExecutor, ToolCallExecutor}
import com.tark.ports.shared.serialization.Sink
import com.tark.ui.{AgentAction, AgentTask}
import fs2.Stream

import java.nio.file.Path

final class DefaultAgentBackend[F[_]: Sync] private (
  sessionRef: Ref[F, Session],
  updateCompletionsRef: Ref[F, List[String] => F[Unit]],
  usageRef: Ref[F, OpenAIUsage],
  reactEngine: ReActLoopEngine[F]
)(using
  sink: Sink[F, Context, Path],
  summarizer: EpisodicMemorySummarizer[F],
  commandExecutor: CommandExecutor[F],
  clock: Clock[F]
) extends AgentBackend[F] {
  
  private val slashCommandBackend =
    SlashCommandBackend[F](actions => emitActions(actions*), sessionRef, commandExecutor, clock, updateCompletionsRef)

  import DefaultAgentBackend.*

  override def registerCompletions(update: List[String] => F[Unit]): F[Unit] =
    slashCommandBackend.registerCompletions(update)

  override def handleInput(input: String): Stream[F, AgentTask[F]] = {
    val clean = input.trim
    if clean.isEmpty then Stream.empty
    else if clean.startsWith("/") then slashCommandBackend.handleInput(clean)
    else processPrompt(input)
  }

  private def processPrompt(input: String): Stream[F, AgentTask[F]] =
    Stream.eval(sessionRef.get).flatMap { session =>
      val userMessage = OpenAIMessage(role = "user", content = Some(input))
      val initialMessages = historyMessages(session.context) :+ userMessage

      Stream.eval(Ref.of[F, Option[ConversationResult]](None)).flatMap { resultRef =>
        reactEngine.runConversation(session.context, initialMessages, depth = 0, Vector.empty, resultRef) ++
          Stream.emit(persistConversationTask(session, resultRef))
      }
    }

  private def persistConversationTask(session: Session, resultRef: Ref[F, Option[ConversationResult]]): AgentTask[F] =
    AgentTask(
      description = Some("Persisting session"),
      action =
        Stream.eval {
          resultRef.get.flatMap {
            case Some(result) =>
              val updatedContext = updateContextAfterConversation(session.context, result)
              val updatedSession = session.copy(context = updatedContext)
              sink.write(updatedContext, session.sessionPath) >> sessionRef.set(updatedSession)
            case None =>
              Sync[F].unit
          }
        }.drain
    )

  private def emitActions(actions: AgentAction[F]*): Stream[F, AgentTask[F]] =
    Stream.emit(AgentTask(description = None, action = Stream.emits(actions.toVector)))

  private def historyMessages(context: Context): List[OpenAIMessage] =
    context.memory.working.flatMap(_.messages.some.filter(_.nonEmpty)).getOrElse {
      context.history.flatMap { interaction =>
        List(
          OpenAIMessage(role = "user", content = Some(interaction.input)),
          OpenAIMessage(role = "assistant", content = Some(interaction.output))
        )
      }
    }

  private def updateContextAfterConversation(context: Context, result: ConversationResult): Context = {
    val currentAgentState = context.memory.working.getOrElse(AgentState())
    val nextAgentState = currentAgentState.copy(
      messages = result.messages,
      candidateAnswer = result.finalAnswer,
      done = result.finalAnswer.isDefined,
      reasonForStop = result.finalAnswer.map(_ => "assistant_response")
    )
    context.copy(
      history = context.history ++ result.interactions,
      memory = context.memory.copy(working = Some(nextAgentState))
    )
  }
}

object DefaultAgentBackend {

  def create[F[_]: Sync](session: Session)(using
    sink: Sink[F, Context, Path],
    summarizer: EpisodicMemorySummarizer[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F],
    llmClient: LlmClient[F],
    streamingLlmClient: StreamingLlmClient[F]
  ): F[DefaultAgentBackend[F]] =
    for {
      sessionRef <- Ref.of[F, Session](session)
      updateRef <- Ref.of[F, List[String] => F[Unit]](_ => Sync[F].unit)
      usageRef <- Ref.of[F, OpenAIUsage](OpenAIUsage(0, 0, 0))
      streamingHandler = StreamingResponseHandler[F](streamingLlmClient, llmClient, usageRef)
      toolCallExecutor = DefaultToolCallExecutor[F](commandExecutor)
      reactEngine = ReActLoopEngine[F](streamingHandler, toolCallExecutor, clock)
    } yield DefaultAgentBackend(sessionRef, updateRef, usageRef, reactEngine)

  def createWithFallbackStreaming[F[_]: Sync](session: Session)(using
    sink: Sink[F, Context, Path],
    summarizer: EpisodicMemorySummarizer[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F],
    llmClient: LlmClient[F]
  ): F[DefaultAgentBackend[F]] = {
    given StreamingLlmClient[F] = StreamingLlmClient.fromBuffered(llmClient)
    create[F](session)
  }
}
