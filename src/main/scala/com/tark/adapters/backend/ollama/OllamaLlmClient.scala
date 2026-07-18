package com.tark.adapters.backend.ollama

import cats.effect.IO
import com.tark.domain.tool.*
import com.tark.domain.tool.{OpenAIRequest, OpenAIResponse, ToolCall}
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, LlmStreamEvent, Prompt, StreamingLlmClient}
import fs2.Stream
import fs2.text
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.decode
import sttp.capabilities.fs2.Fs2Streams
import sttp.client3.*
import sttp.client3.circe.*

class OllamaLlmClient(
  backend: SttpBackend[IO, Fs2Streams[IO]],
  modelName: String = "qwen3-coder:30b",
  baseUrl: String = "http://localhost:11434/v1/chat/completions"
) extends LlmClient[IO] with StreamingLlmClient[IO] {

  override def streaming: Option[StreamingLlmClient[IO]] = Some(this)

  override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] = {
    val request = basicRequest
      .post(uri"$baseUrl")
      .body(OpenAIRequest(
        model = modelName,
        messages = prompt.messages,
        tools = prompt.availableTools
      ))
      .response(asJson[OpenAIResponse])

    backend.send(request).map { response =>
      response.body match {
        case Right(payload) =>
          LLMResponse(
            content = payload.choices.flatMap(_.message.content).mkString("\n"),
            results = payload.choices.flatMap(_.message.tool_calls.getOrElse(Nil)),
            usage = payload.usage
          )
        case Left(error) =>
          LLMResponse(
            content = s"HTTP Error running Ollama: ${error.getMessage}",
            results = List.empty,
            usage = OpenAIUsage(0, 0, 0)
          )
      }
    }
  }

  override def chatStream(prompt: Prompt): Stream[IO, LlmStreamEvent] = {
    val request = basicRequest
      .post(uri"$baseUrl")
      .body(OpenAIRequest(
        model = modelName,
        messages = prompt.messages,
        tools = prompt.availableTools,
        stream = Some(true),
        stream_options = Some(StreamOptions(include_usage = true))
      ))
      .response(asStreamUnsafe(Fs2Streams[IO]))
      .readTimeout(scala.concurrent.duration.Duration.Inf)

    Stream.eval(backend.send(request)).flatMap { response =>
      response.body match {
        case Left(error) =>
          Stream.emit(LlmStreamEvent.Failed(error))
        case Right(bytes) =>
          bytes
            .through(text.utf8.decode)
            .through(text.lines)
            .map(_.trim)
            .filter(line => line.startsWith("data:"))
            .map(_.stripPrefix("data:").trim)
            .filter(_.nonEmpty)
            .flatMap {
              case "[DONE]" =>
                Stream.emit(LlmStreamEvent.Completed())
              case payload =>
                OllamaLlmClient.eventsFromPayload(payload) match {
                  case Right(events) => Stream.emits(events)
                  case Left(error)   => Stream.raiseError[IO](IllegalArgumentException(error))
                }
            }
      }
    }
  }
}

object OllamaLlmClient {
  private final case class ChatCompletionChunk(
    choices: List[StreamChoice],
    usage: Option[OpenAIUsage] = None
  )
  private final case class StreamChoice(index: Int, delta: StreamDelta, finish_reason: Option[String] = None)
  private final case class StreamDelta(
    role: Option[String] = None,
    content: Option[String] = None,
    tool_calls: Option[List[StreamToolCall]] = None
  )
  private final case class StreamToolCall(
    index: Int,
    id: Option[String] = None,
    `type`: Option[String] = None,
    function: Option[StreamFunctionDelta] = None
  )
  private final case class StreamFunctionDelta(
    name: Option[String] = None,
    arguments: Option[String] = None
  )

  private given Decoder[ChatCompletionChunk] = deriveDecoder
  private given Decoder[StreamChoice] = deriveDecoder
  private given Decoder[StreamDelta] = deriveDecoder
  private given Decoder[StreamToolCall] = deriveDecoder
  private given Decoder[StreamFunctionDelta] = deriveDecoder

  private[ollama] def eventsFromPayload(payload: String): Either[String, List[LlmStreamEvent]] =
    decode[ChatCompletionChunk](payload).left.map(_.getMessage).map { chunk =>
      val contentEvents = chunk.choices.flatMap { choice =>
        val contentEvents =
          choice.delta.content.filter(_.nonEmpty).map(LlmStreamEvent.ContentDelta.apply).toList
        val toolEvents =
          choice.delta.tool_calls.getOrElse(Nil).map { toolCall =>
            LlmStreamEvent.ToolCallDelta(
              index = toolCall.index,
              id = toolCall.id,
              callType = toolCall.`type`,
              name = toolCall.function.flatMap(_.name),
              argumentsChunk = toolCall.function.flatMap(_.arguments)
            )
          }
        contentEvents ++ toolEvents
      }
      val usageEvent = chunk.usage.map(LlmStreamEvent.Usage.apply).toList
      contentEvents ++ usageEvent
    }
}
