package com.tark.application.chat

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.AgentState
import com.tark.application.time.Clock
import com.tark.domain.Interaction
import com.tark.domain.context.{Context, Session}
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolResult}
import com.tark.ports.inbound.tool.{InputProcessor, SlashCommandRouter}
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, Prompt}
import com.tark.ports.outbound.tool.CommandExecutor
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.shared.ui.{ChatState, Message}

import java.nio.file.Path

object DefaultInputProcessor {
  private val MaxToolDepth = 10

  private case class ConversationResult(
    messages: List[OpenAIMessage],
    uiMessages: Vector[Message],
    interactions: Vector[Interaction],
    finalAnswer: Option[String]
  )

  given default[F[_]: Sync](using
    sink: Sink[F, Context, Path],
    slashRouter: SlashCommandRouter[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F]
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
        val userMessage = OpenAIMessage(role = "user", content = Some(input))
        val initialMessages = historyMessages(session.context) :+ userMessage
        val initialState = state.copy(
          history = state.history :+ Message.User(input),
          prompt = "",
          scrollOffset = 0,
          currentThought = None
        )

        for {
          _ <- redraw(initialState)
          result <- runConversation(session.context, initialMessages, depth = 0, Vector.empty, redraw, initialState)
          finalState = initialState.copy(
            history = initialState.history ++ result.uiMessages,
            currentThought = None
          )
          updatedContext = updateContextAfterConversation(session.context, result)
          _ <- sink.write(updatedContext, session.sessionPath)
          _ <- redraw(finalState)
        } yield Some((finalState, session.copy(context = updatedContext)))
      }
    }

    private def runConversation(
      context: Context,
      messages: List[OpenAIMessage],
      depth: Int,
      accumulatedInteractions: Vector[Interaction],
      redraw: ChatState => F[Unit],
      visibleState: ChatState
    )(using llmClient: LlmClient[F], sync: Sync[F], clock: Clock[F]): F[ConversationResult] = {
      if (depth >= MaxToolDepth) {
        Sync[F].pure(
          ConversationResult(
            messages = messages,
            uiMessages = Vector(Message.System("Error: Maximum tool call execution depth exceeded.")),
            interactions = accumulatedInteractions,
            finalAnswer = Some("Error: Maximum tool call execution depth exceeded.")
          )
        )
      } else {
        llmClient.chat(Prompt(messages, context.tools)).flatMap { response =>
          val assistantMessage = OpenAIMessage(
            role = "assistant",
            content = response.content.some.filter(_.nonEmpty),
            tool_calls = response.results.some.filter(_.nonEmpty)
          )

          if (response.results.isEmpty) {
            clock.realTimeMillis.map { now =>
              val finalMessages = messages :+ assistantMessage
              val interaction = Interaction(
                id = s"interaction_$now",
                input = messages.lastOption.flatMap(_.content).getOrElse(""),
                output = response.content,
                timestamp = now,
                toolName = "llm_completion"
              )
              ConversationResult(
                messages = finalMessages,
                uiMessages = responseToMessages(response),
                interactions = accumulatedInteractions :+ interaction,
                finalAnswer = Some(response.content)
              )
            }
          } else {
            response.results.traverse(executeTool(context)).flatMap { toolResults =>
              val renderedTools = toolResults.map(result => Message.System(result.content)).toVector
              val toolMessages = response.results.zip(toolResults).map { case (toolCall, result) =>
                OpenAIMessage(
                  role = "tool",
                  content = Some(result.content),
                  tool_call_id = Some(toolCall.id)
                )
              }
              val nextVisible = visibleState.copy(
                history = visibleState.history ++ responseToMessages(response) ++ renderedTools,
                currentThought = response.content.some.filter(_.nonEmpty)
              )

              for {
                _ <- redraw(nextVisible)
                now <- clock.realTimeMillis
                interactions = response.results.zip(toolResults).zipWithIndex.map { case ((toolCall, result), idx) =>
                  Interaction(
                    id = s"interaction_${now}_$idx",
                    input = toolCall.function.arguments,
                    output = result.content,
                    timestamp = now + idx,
                    toolName = toolCall.function.name
                  )
                }.toVector
                rest <- runConversation(
                  context,
                  messages ++ List(assistantMessage) ++ toolMessages,
                  depth + 1,
                  accumulatedInteractions ++ interactions,
                  redraw,
                  nextVisible
                )
              } yield {
                rest.copy(
                  uiMessages = responseToMessages(response) ++ renderedTools ++ rest.uiMessages
                )
              }
            }
          }
        }
      }
    }

    private def executeTool(context: Context)(toolCall: ToolCall)(using sync: Sync[F]): F[ToolResult] =
      if (toolCall.function.name == "command_executor") {
        commandExecutor.execute(context, toolCall)
      } else {
        Sync[F].pure(ToolResult(s"Tool '${toolCall.function.name}' is not available."))
      }

    private def responseToMessages(response: LLMResponse[ToolCall]): Vector[Message] =
      Option(response.content).filter(_.nonEmpty).map(content => Vector(Message.AI(content))).getOrElse(Vector.empty)

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
}
