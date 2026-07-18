package com.tark.ports.outbound.backend

import cats.Monad
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolDefinition}
import fs2.Stream
import io.circe.parser

trait LLMClient[F[_]: Monad, I, A]:
  def chat(prompt: I): F[A]
  def streaming: Option[StreamingLlmClient[F]] = None

case class Prompt(
  messages: List[OpenAIMessage],
  availableTools: List[ToolDefinition]
)

case class LLMResponse[A](
  content: String,
  results: List[A]
)

type LlmClient[F[_]] = LLMClient[F, Prompt, LLMResponse[ToolCall]]

sealed trait LlmStreamEvent

object LlmStreamEvent:
  final case class ContentDelta(text: String) extends LlmStreamEvent
  final case class ThinkingDelta(text: String) extends LlmStreamEvent
  final case class ToolCallDelta(
    index: Int,
    id: Option[String] = None,
    callType: Option[String] = None,
    name: Option[String] = None,
    argumentsChunk: Option[String] = None
  ) extends LlmStreamEvent
  final case class Completed(response: Option[LLMResponse[ToolCall]] = None) extends LlmStreamEvent
  final case class Failed(message: String) extends LlmStreamEvent

trait StreamingLlmClient[F[_]]:
  def chatStream(prompt: Prompt): Stream[F, LlmStreamEvent]

object StreamingLlmClient:
  def fromBuffered[F[_]](client: LlmClient[F]): StreamingLlmClient[F] =
    new StreamingLlmClient[F] {
      override def chatStream(prompt: Prompt): Stream[F, LlmStreamEvent] =
        Stream.eval(client.chat(prompt)).flatMap { response =>
          val contentEvents =
            Option(response.content)
              .filter(_.nonEmpty)
              .map(content => Stream.emit(LlmStreamEvent.ContentDelta(content)))
              .getOrElse(Stream.empty)
          contentEvents ++ Stream.emit(LlmStreamEvent.Completed(Some(response)))
        }
    }

final case class ToolCallAccumulator private (
  builders: Map[Int, ToolCallAccumulator.Builder]
) {
  def add(delta: LlmStreamEvent.ToolCallDelta): ToolCallAccumulator =
    copy(builders = builders.updated(delta.index, builders.getOrElse(delta.index, ToolCallAccumulator.Builder(delta.index)).append(delta)))

  def complete: Either[List[String], List[ToolCall]] = {
    val completed = builders.values.toList.sortBy(_.index).map(_.toToolCall)
    val errors = completed.collect { case Left(error) => error }
    if errors.nonEmpty then Left(errors) else Right(completed.collect { case Right(call) => call })
  }
}

object ToolCallAccumulator {
  final case class Builder(
    index: Int,
    id: Option[String] = None,
    callType: Option[String] = None,
    name: Option[String] = None,
    arguments: String = ""
  ) {
    def append(delta: LlmStreamEvent.ToolCallDelta): Builder =
      copy(
        id = delta.id.orElse(id),
        callType = delta.callType.orElse(callType),
        name = delta.name.orElse(name),
        arguments = arguments + delta.argumentsChunk.getOrElse("")
      )

    def toToolCall: Either[String, ToolCall] = {
      val resolvedType = callType.getOrElse("function")
      val errors = List(
        Option.when(id.forall(_.trim.isEmpty))(s"Tool call at index $index is missing id."),
        Option.when(resolvedType != "function")(s"Tool call at index $index has unsupported type '$resolvedType'."),
        Option.when(name.forall(_.trim.isEmpty))(s"Tool call at index $index is missing function name."),
        parser.parse(arguments).left.toOption.map(error => s"Tool call at index $index has invalid JSON arguments: ${error.getMessage}")
      ).flatten

      if errors.nonEmpty then Left(errors.mkString(" "))
      else
        Right(
          ToolCall(
            id = id.get.trim,
            `type` = resolvedType,
            function = com.tark.domain.tool.ToolCallFunction(name.get.trim, arguments)
          )
        )
    }
  }

  val empty: ToolCallAccumulator = ToolCallAccumulator(Map.empty)
}
