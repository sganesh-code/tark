package com.tark.adapters.backend.ollama

import cats.effect.IO
import com.tark.domain.Interaction
import OllamaProtocol.given
import com.tark.domain.tool.{Tool, ToolCallRequest}
import com.tark.ports.outbound.backend.*
import io.circe.Json
import sttp.client3.*
import sttp.client3.circe.*

class OllamaLlmClient(
                       backend: SttpBackend[IO, Any],
                       modelName: String = "qwen3-coder:30b",
                       baseUrl: String = "http://localhost:11434/v1/chat/completions"
                     ) extends LlmClient[IO] {

  given LlmRequestCreator[OllamaMessage, OllamaRequest] with {
    override def createRequest(modelName: String, messages: List[OllamaMessage], format: Option[String]): OllamaRequest = {
      OllamaRequest(
        model = modelName,
        messages = messages,
        tools = None,
        format = format
      )
    }
  }

  given LlmExecutor[IO, OllamaRequest, OllamaResponse] with {
    override def execute(requestBody: OllamaRequest): IO[Either[String, OllamaResponse]] = {
      val request = basicRequest
        .post(uri"$baseUrl")
        .body(requestBody)
        .response(asJson[OllamaResponse])

      backend.send(request).map { response =>
        response.body match {
          case Right(successPayload) => Right(successPayload)
          case Left(error) => Left(s"HTTP Error running Ollama: ${error.getMessage}")
        }
      }
    }
  }

  given LlmResponseParser[OllamaResponse] with {
    override def parseResponse(response: OllamaResponse): Either[String, List[ToolCallRequest]] = {
      val msg = response.choices.head.message
      val rawContent = msg.content.getOrElse("").trim
      
      import io.circe.parser._
      parse(rawContent) match {
        case Right(json) =>
          val cursor = json.hcursor
          cursor.get[Json]("action") match {
            case Right(actJson) =>
              val actCursor = actJson.hcursor
              val name = actCursor.get[String]("name").getOrElse("")
              val argsJson = actCursor.get[Json]("arguments").getOrElse(Json.obj())
              
              val argsMap = argsJson.asObject.map { obj =>
                obj.toMap.map { (k, v) =>
                  k -> v.asString.getOrElse(v.toString())
                }
              }.getOrElse(Map.empty[String, String])
              
              Right(List(ToolCallRequest(name, argsMap)))
              
            case Left(_) =>
              Left(rawContent)
          }
        case Left(_) =>
          Left(rawContent)
      }
    }
  }

  override def getCompletion(
    prompt: String,
    history: List[Interaction],
    systemPrompt: String,
    tools: List[Tool]
  ): IO[Either[String, List[ToolCallRequest]]] = {
    LlmPipeline
      .client[IO, OllamaMessage, OllamaRequest, OllamaResponse](modelName, Some("json"))
      .getCompletion(prompt, history, systemPrompt, tools)
  }
}
