package com.tark.adapters.backend.ollama

import cats.effect.IO
import com.tark.domain.tool.{FunctionDefinition, FunctionParameters, FunctionProperty, Tool, ToolCallRequest, ToolDefinition}
import com.tark.ports.outbound.react.{ReActResponse, ReActStrategy}
import com.tark.ports.shared.tool.ToToolDescription

object OllamaReActStrategy {

  private def stripMarkdownCodeBlocks(s: String): String = {
    val trimmed = s.trim
    val codeBlockRegex = """(?s).*?```(?:json)?\s*\n(.*?)\n\s*```.*?""".r
    trimmed match {
      case codeBlockRegex(jsonStr) => jsonStr.trim
      case _ =>
        val jsonRegex = """(?s).*?(\{.*\}).*?""".r
        trimmed match {
          case jsonRegex(jsonStr) => jsonStr.trim
          case _                  => trimmed
        }
    }
  }

  /**
   * JSON-forced ReAct strategy for local Ollama engines.
   */
  class JsonReActStrategy extends ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] {
    override def prepareRequest(
      model: String,
      messages: List[OllamaMessage],
      tools: List[Tool],
      systemPrompt: String
    ): OllamaRequest =
      OllamaRequest(
        model = model,
        messages = messages,
        tools = None,
        format = Some("json"),
        response_format = Some(ResponseFormat("json_object"))
      )

    override def parseResponse(
      rawContent: String,
      response: OllamaResponse,
      tools: List[Tool]
    ): IO[Either[String, ReActResponse]] = IO.blocking {
      import io.circe.parser.*
      val stripped = stripMarkdownCodeBlocks(rawContent)

      val parseResult = parse(stripped)

      val debugLogPath = java.nio.file.Path.of("target/sessions/parser-debug.log")
      val logText = s"""=== PARSE TURN ===
RawContent:
$rawContent

Stripped:
$stripped

ParseResult:
$parseResult
==================\n"""
      java.nio.file.Files.writeString(
        debugLogPath,
        logText,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      )

      parseResult match {
        case Right(json) =>
          val cursor = json.hcursor
          val thought = cursor.get[String]("thought").getOrElse("Executing tool call...")

          val toolName = cursor.get[String]("tool").toOption
            .orElse {
              cursor.get[io.circe.Json]("action").toOption.flatMap { act =>
                act.hcursor.get[String]("name").toOption
              }
            }
            .map(_.trim)
            .filter(_.nonEmpty)
            .getOrElse("")

          val argsJsonOpt = cursor.get[io.circe.Json]("arguments").toOption
            .orElse {
              cursor.get[io.circe.Json]("action").toOption.flatMap { act =>
                act.hcursor.get[io.circe.Json]("arguments").toOption
              }
            }
          val argsJson = argsJsonOpt.getOrElse(io.circe.Json.obj())

          val argsMap = argsJson.asObject.map { obj =>
            obj.toMap.map { (k, v) =>
              k -> v.asString.getOrElse(v.toString())
            }
          }.getOrElse(Map.empty[String, String])

          val isConclude = toolName == "conclude_task" || cursor.get[String]("finish").isRight
          val finalAnswer = if (toolName == "conclude_task") {
            argsMap.getOrElse("final_answer", "")
          } else {
            cursor.get[String]("finish").getOrElse("")
          }

          if (isConclude) {
            Right(ReActResponse(thought, Left(finalAnswer)))
          } else if (toolName.nonEmpty) {
            val toolCall = ToolCallRequest(toolName, argsMap)
            Right(ReActResponse(thought, Right(toolCall)))
          } else {
            Right(ReActResponse(thought, Left(rawContent)))
          }

        case Left(_) =>
          Right(ReActResponse(rawContent.trim, Left(rawContent.trim)))
      }
    }
  }

  /**
   * Native OpenAI/Ollama function-calling strategy for models that support function calling.
   */
  class NativeReActStrategy extends ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] {
    override def prepareRequest(
      model: String,
      messages: List[OllamaMessage],
      tools: List[Tool],
      systemPrompt: String
    ): OllamaRequest = {
      val concludeTool = ToolDefinition(
        function = FunctionDefinition(
          name = "conclude_task",
          description = "Call this tool when you have achieved the goal or completed the task.",
          parameters = FunctionParameters(
            properties = Map(
              "final_answer" -> FunctionProperty("string", "The final complete answer or outcome to present to the user")
            ),
            required = List("final_answer")
          )
        )
      )

      val descOps = summon[ToToolDescription[Tool]]
      val nativeTools = tools.map(descOps.describe) :+ concludeTool

      OllamaRequest(
        model = model,
        messages = messages,
        tools = if (tools.nonEmpty) Some(nativeTools) else None,
        format = None,
        tool_choice = if (tools.nonEmpty) Some("required") else None
      )
    }

    override def parseResponse(
      rawContent: String,
      response: OllamaResponse,
      tools: List[Tool]
    ): IO[Either[String, ReActResponse]] = IO {
      val msg = response.choices.head.message
      msg.tool_calls match {
        case Some(calls) if calls.nonEmpty =>
          val call = calls.head
          import io.circe.parser.*
          val argsMap = parse(call.function.arguments) match {
            case Right(json) =>
              json.asObject.map { obj =>
                obj.toMap.map { (k, v) =>
                  k -> v.asString.getOrElse(v.toString())
                }
              }.getOrElse(Map.empty[String, String])
            case _ =>
              Map.empty[String, String]
          }

          val contentThought = msg.content.filter(_.trim.nonEmpty).getOrElse("")
          val thought = argsMap.get("thought")
            .orElse(if (contentThought.trim.nonEmpty) Some(contentThought) else None)
            .getOrElse("Executing tool call...")
          val remainingArgs = argsMap - "thought"

          if (call.function.name == "conclude_task") {
            val finalAnswer = argsMap.getOrElse("final_answer", "")
            Right(ReActResponse(thought, Left(finalAnswer)))
          } else {
            val toolCall = ToolCallRequest(call.function.name, remainingArgs)
            Right(ReActResponse(thought, Right(toolCall)))
          }

        case _ =>
          Right(ReActResponse(rawContent.trim, Left(rawContent.trim)))
      }
    }
  }

  given defaultStrategy: ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] =
    new JsonReActStrategy()
}
