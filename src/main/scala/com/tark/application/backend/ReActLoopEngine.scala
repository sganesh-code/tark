package com.tark.application.backend

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.Interaction
import com.tark.domain.context.Context
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolResult}
import com.tark.ports.outbound.backend.*
import com.tark.ports.outbound.tool.ToolCallExecutor
import com.tark.ui.{AgentAction, AgentTask}
import fs2.Stream

final case class ConversationResult(
  messages: List[OpenAIMessage],
  interactions: Vector[Interaction],
  finalAnswer: Option[String]
)

final class ReActLoopEngine[F[_]: Sync](
  streamingHandler: StreamingResponseHandler[F],
  toolCallExecutor: ToolCallExecutor[F],
  clock: Clock[F]
) {
  import ReActLoopEngine.*

  def runConversation(
    context: Context,
    messages: List[OpenAIMessage],
    depth: Int,
    accumulatedInteractions: Vector[Interaction],
    resultRef: Ref[F, Option[ConversationResult]]
  ): Stream[F, AgentTask[F]] =
    if depth >= MaxToolDepth then
      val message = "Error: Maximum tool call execution depth exceeded."
      val result = ConversationResult(
        messages = messages,
        interactions = accumulatedInteractions,
        finalAnswer = Some(message)
      )
      Stream.emit(
        AgentTask(
          description = Some("Stopping conversation"),
          action = Stream.eval(resultRef.set(Some(result))).drain ++ Stream.emit(AgentAction.SystemMessage(message))
        )
      )
    else
      Stream.eval(Ref.of[F, Option[LLMResponse[ToolCall]]](None)).flatMap { responseRef =>
        val basePrompt = Prompt(messages, context.tools)
        val goalQuery = context.memory.working.map(_.goal).filter(_.nonEmpty).getOrElse(originalUserInput(messages))
        val groundedPrompt = GroundedPrompt.compile(basePrompt, context.memory, goalQuery)

        val responseTask = AgentTask(
          description = Some(if depth == 0 then "Waiting for assistant response" else "Waiting for assistant response after tool results"),
          action =
            streamingHandler.collectStreamingResponse(groundedPrompt, responseRef)
        )

        Stream.emit(responseTask) ++
          Stream.eval(responseRef.get.flatMap {
            case Some(response) => Sync[F].pure(response)
            case None           => Sync[F].raiseError(new IllegalStateException("Assistant response task did not complete."))
          }).flatMap { response =>
            val assistantMessage = OpenAIMessage(
              role = "assistant",
              content = response.content.some.filter(_.nonEmpty),
              tool_calls = response.results.some.filter(_.nonEmpty)
            )

            if response.results.isEmpty then
              Stream.emit(finalizeConversationTask(messages :+ assistantMessage, accumulatedInteractions, response, resultRef))
            else
              Stream.eval(Ref.of[F, Vector[(ToolCall, ToolResult)]](Vector.empty)).flatMap { toolResultRef =>
                Stream.eval(
                  response.results.traverse { toolCall =>
                    Ref.of[F, Option[ToolResult]](None).map { resultRef =>
                      val toolActionStream = toolCallExecutor.execute(context, toolCall, resultRef)

                      AgentTask(
                        description = None,
                        action =
                          Stream.emit(AgentAction.ToolCallStart(toolCall.function.name, toolCall.function.arguments)) ++
                            toolActionStream ++
                            Stream.eval(resultRef.get).flatMap {
                              case Some(result) =>
                                Stream.eval(toolResultRef.update(_ :+ (toolCall, result))).drain ++
                                  Stream.emit(AgentAction.ToolCallOutput(result.content))
                              case None =>
                                Stream.empty
                            } ++
                            Stream.emit(AgentAction.ToolCallEnd())
                      )
                    }
                  }
                ).flatMap { toolTasks =>
                  Stream.emits(toolTasks) ++
                    Stream.eval(toolResultRef.get).flatMap { toolResults =>
                      val toolMessages = toolResults.map { case (toolCall, result) =>
                        OpenAIMessage(
                          role = "tool",
                          content = Some(result.content),
                          tool_call_id = Some(toolCall.id)
                        )
                      }.toList

                      Stream.eval(clock.realTimeMillis).flatMap { now =>
                        val interactions = toolResults.zipWithIndex.map { case ((toolCall, result), idx) =>
                          Interaction(
                            id = s"interaction_${now}_$idx",
                            input = toolCall.function.arguments,
                            output = result.content,
                            timestamp = now + idx,
                            toolName = toolCall.function.name
                          )
                        }

                        runConversation(
                          context,
                          messages ++ List(assistantMessage) ++ toolMessages,
                          depth + 1,
                          accumulatedInteractions ++ interactions,
                          resultRef
                        )
                      }
                    }
                }
              }
          }
      }

  private def finalizeConversationTask(
    messages: List[OpenAIMessage],
    accumulatedInteractions: Vector[Interaction],
    response: LLMResponse[ToolCall],
    resultRef: Ref[F, Option[ConversationResult]]
  ): AgentTask[F] =
    AgentTask(
      description = Some("Finalizing assistant response"),
      action =
        Stream.eval {
          clock.realTimeMillis.flatMap { now =>
            val interaction = Interaction(
              id = s"interaction_$now",
              input = originalUserInput(messages),
              output = response.content,
              timestamp = now,
              toolName = "llm_completion"
            )
            resultRef.set(
              Some(
                ConversationResult(
                  messages = messages,
                  interactions = accumulatedInteractions :+ interaction,
                  finalAnswer = Some(response.content)
                )
              )
            )
          }
        }.drain
    )

  private def originalUserInput(messages: List[OpenAIMessage]): String =
    messages.find(_.role == "user").flatMap(_.content).getOrElse("")
}

object ReActLoopEngine {
  private val MaxToolDepth = 10
}
