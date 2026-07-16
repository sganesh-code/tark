package com.tark.adapters.backend.ollama

import cats.effect.IO
import com.tark.domain.tool.{OpenAIRequest, OpenAIResponse, ToolCall}
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, Prompt}
import sttp.client3.*
import sttp.client3.circe.*

class OllamaLlmClient(
  backend: SttpBackend[IO, Any],
  modelName: String = "qwen3-coder:30b",
  baseUrl: String = "http://localhost:11434/v1/chat/completions"
) extends LlmClient[IO] {

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
            results = payload.choices.flatMap(_.message.tool_calls.getOrElse(Nil))
          )
        case Left(error) =>
          LLMResponse(
            content = s"HTTP Error running Ollama: ${error.getMessage}",
            results = List.empty
          )
      }
    }
  }
}
