package com.tark.adapters.backend.ollama

import cats.effect.IO
import com.tark.domain.Interaction
import OllamaProtocol.given
import com.tark.domain.tool.{Tool, ToolCallRequest}
import com.tark.ports.outbound.backend.*
import com.tark.ports.outbound.react.{ReActLlmClient, ReActResponse, ReActStrategy}
import sttp.client3.*
import sttp.client3.circe.*

class OllamaReActLlmClient(
                           backend: SttpBackend[IO, Any],
                           modelName: String = "qwen3-coder:30b",
                           baseUrl: String = "http://localhost:11434/v1/chat/completions"
                           )(using strategy: ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] = new OllamaReActStrategy.JsonReActStrategy()) extends ReActLlmClient[IO] {

  override def getCompletion(
    prompt: String,
    history: List[Interaction],
    systemPrompt: String,
    tools: List[Tool]
  ): IO[Either[String, ReActResponse]] = {
    
    val historyMessages = history.toMessages[OllamaMessage]
    val systemMsg = OllamaMessage("system", Some(systemPrompt))
    val currentMsg = OllamaMessage("user", Some(prompt))
    val allMessages = List(systemMsg) ++ historyMessages :+ currentMsg

    val requestBody = strategy.prepareRequest(modelName, allMessages, tools, systemPrompt)

    val request = basicRequest
      .post(uri"$baseUrl")
      .body(requestBody)
      .response(asJson[OllamaResponse])

    backend.send(request).flatMap { response =>
      response.body match {
        case Right(successPayload) =>
          val msg = successPayload.choices.head.message
          val rawContent = msg.content.getOrElse("")
          strategy.parseResponse(rawContent, successPayload, tools)
        case Left(error) =>
          IO.pure(Left(s"HTTP Error running Ollama: ${error.getMessage}"))
      }
    }
  }
}
