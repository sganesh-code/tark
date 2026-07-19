package com.tark.application.backend

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.domain.tool.{OpenAIUsage, ToolCall}
import com.tark.ports.outbound.backend.*
import com.tark.ui.AgentAction
import fs2.Stream

final class StreamingResponseHandler[F[_]: Sync](
  streamingLlmClient: StreamingLlmClient[F],
  llmClient: LlmClient[F],
  usageRef: Ref[F, OpenAIUsage]
) {
  import StreamingResponseHandler.*

  private def responseToActions(response: LLMResponse[ToolCall]): Vector[AgentAction[F]] =
    Option(response.content)
      .filter(_.nonEmpty)
      .map(content => Vector(AgentAction.Log(content)))
      .getOrElse(Vector.empty)

  private def updateUsageAndGetStatus(usage: OpenAIUsage): F[String] =
    usageRef.updateAndGet(curr =>
      OpenAIUsage(
        curr.prompt_tokens + usage.prompt_tokens,
        curr.completion_tokens + usage.completion_tokens,
        curr.total_tokens + usage.total_tokens
      )
    ).map(u => s"LLM Usage: Prompt ${u.prompt_tokens} | Completion ${u.completion_tokens} | Total ${u.total_tokens}")

  def collectStreamingResponse(
    prompt: Prompt,
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]]
  ): Stream[F, AgentAction[F]] = {
    val responseStream = Stream
      .eval(Ref.of[F, StreamingResponseState](StreamingResponseState.empty))
      .flatMap { stateRef =>
        streamingLlmClient
          .chatStream(prompt)
          .evalMapAccumulate(Vector.empty[AgentAction[F]]) { case (_, event) =>
            handleStreamEvent(prompt, event, stateRef, responseRef).map(actions => actions -> actions)
          }
          .flatMap { case (_, actions) => Stream.emits(actions) }
          .handleErrorWith { error =>
            fallbackBufferedResponse(prompt, responseRef, s"Streaming failed: ${error.getMessage}. Falling back to buffered response.")
          }
      }

    responseStream ++ Stream.eval(responseRef.get).flatMap {
      case Some(response) =>
        Stream.eval(updateUsageAndGetStatus(response.usage)).flatMap { statusText =>
          Stream.emit(AgentAction.StatusUpdate(statusText))
        }
      case None =>
        Stream.empty
    }
  }

  private def handleStreamEvent(
    prompt: Prompt,
    event: LlmStreamEvent,
    stateRef: Ref[F, StreamingResponseState],
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]]
  ): F[Vector[AgentAction[F]]] =
    event match {
      case LlmStreamEvent.ContentDelta(text) =>
        stateRef.update(_.appendContent(text)).as(Vector(AgentAction.AssistantDelta(text)))

      case LlmStreamEvent.ThinkingDelta(_) =>
        Sync[F].pure(Vector.empty)

      case delta: LlmStreamEvent.ToolCallDelta =>
        stateRef.update(_.appendToolDelta(delta)).as(Vector.empty)

      case LlmStreamEvent.Usage(usage) =>
        stateRef.update(_.withUsage(usage)).as(Vector.empty)

      case LlmStreamEvent.Completed(Some(response)) =>
        responseRef.set(Some(response)).as(Vector(AgentAction.AssistantEnd()))

      case LlmStreamEvent.Completed(None) =>
        stateRef.get.flatMap { state =>
          state.toResponse match {
            case Right(response) =>
              responseRef.set(Some(response)).as(Vector(AgentAction.AssistantEnd()))
            case Left(errors) =>
              val message = errors.mkString(" ")
              val response = LLMResponse(s"Streaming tool call failed: $message", List.empty[ToolCall], OpenAIUsage(0, 0, 0))
              responseRef.set(Some(response)).as(Vector(AgentAction.AssistantEnd(), AgentAction.SystemMessage(response.content)))
          }
        }

      case LlmStreamEvent.Failed(message) =>
        fallbackBufferedResponseAsList(
          prompt = prompt,
          responseRef = responseRef,
          warning = s"Streaming failed: $message. Falling back to buffered response."
        )
    }

  private def fallbackBufferedResponse(
    prompt: Prompt,
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]],
    warning: String
  ): Stream[F, AgentAction[F]] =
    Stream.emit(AgentAction.SystemMessage(warning)) ++
      Stream.eval(llmClient.chat(prompt).flatTap(response => responseRef.set(Some(response)))).flatMap(response => Stream.emits(responseToActions(response)))

  private def fallbackBufferedResponseAsList(
    prompt: Prompt,
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]],
    warning: String
  ): F[Vector[AgentAction[F]]] =
    llmClient
      .chat(prompt)
      .flatTap(response => responseRef.set(Some(response)))
      .map(response => Vector(AgentAction.SystemMessage(warning)) ++ responseToActions(response))
}

object StreamingResponseHandler {
  final case class StreamingResponseState(
    content: String,
    toolCalls: ToolCallAccumulator,
    usage: OpenAIUsage = OpenAIUsage(0, 0, 0)
  ) {
    def appendContent(delta: String): StreamingResponseState =
      copy(content = content + delta)

    def appendToolDelta(delta: LlmStreamEvent.ToolCallDelta): StreamingResponseState =
      copy(toolCalls = toolCalls.add(delta))

    def withUsage(u: OpenAIUsage): StreamingResponseState =
      copy(usage = u)

    def toResponse: Either[List[String], LLMResponse[ToolCall]] =
      toolCalls.complete.map(calls => LLMResponse(content, calls, usage))
  }

  private object StreamingResponseState {
    val empty: StreamingResponseState = StreamingResponseState("", ToolCallAccumulator.empty)
  }
}
