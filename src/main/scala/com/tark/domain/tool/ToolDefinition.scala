package com.tark.domain.tool

import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*

case class OpenAIMessage(
  role: String,
  content: Option[String] = None,
  tool_calls: Option[List[ToolCall]] = None,
  tool_call_id: Option[String] = None
)

case class OpenAPIFunctionParamsPropertiesPattern(`type`: String)

case class OpenAiFunctionParamsProperties(pattern: OpenAPIFunctionParamsPropertiesPattern)

enum OpenAIFunctionParams:
  case Str(`type`: String = "string", description: String)
  case Object(`type`: String = "object", properties: OpenAiFunctionParamsProperties, description: String)
  case Custom(schema: io.circe.Json)

case class OpenAIFunction(
  name: String,
  description: String,
  parameters: OpenAIFunctionParams
)

sealed trait ToolDefinition {
  def `type`: String
  def function: OpenAIFunction
}

case class McpToolDefinition(
  name: String,
  description: String,
  parameters: OpenAIFunctionParams
) extends ToolDefinition {

  override val `type`: String = "function"
  override val function: OpenAIFunction = OpenAIFunction(name, description, parameters)
}

case class StreamOptions(include_usage: Boolean)

case class OpenAIRequest(
  model: String,
  messages: List[OpenAIMessage],
  tools: List[ToolDefinition],
  stream: Option[Boolean] = None,
  stream_options: Option[StreamOptions] = None
)

case class OpenAIUsage(
  prompt_tokens: Int,
  completion_tokens: Int,
  total_tokens: Int
)

case class ToolCallFunction(
  name: String,
  arguments: String
)

case class ToolCall(id: String, `type`: String, function: ToolCallFunction)

case class ToolResult(content: String)

case class OpenAIResponseMessage(
  role: String,
  content: Option[String] = None,
  tool_calls: Option[List[ToolCall]] = None
)

case class OpenAIResponseChoice(
  index: Int,
  message: OpenAIResponseMessage,
  finish_reason: String
)

case class OpenAIResponse(
  id: String,
  `object`: String,
  created: Long,
  model: String,
  choices: List[OpenAIResponseChoice],
  usage: OpenAIUsage
)

object OpenAPIFunctionParamsPropertiesPattern {
  given Encoder[OpenAPIFunctionParamsPropertiesPattern] = deriveEncoder
  given Decoder[OpenAPIFunctionParamsPropertiesPattern] = deriveDecoder
}

object OpenAiFunctionParamsProperties {
  given Encoder[OpenAiFunctionParamsProperties] = deriveEncoder
  given Decoder[OpenAiFunctionParamsProperties] = deriveDecoder
}

object OpenAIFunctionParams {
  given Encoder[OpenAIFunctionParams] = Encoder.instance {
    case Str(t, d) => Json.obj("type" -> Json.fromString(t), "description" -> Json.fromString(d))
    case Object(t, p, d) => Json.obj("type" -> Json.fromString(t), "properties" -> p.asJson, "description" -> Json.fromString(d))
    case Custom(schema) => schema
  }
  given Decoder[OpenAIFunctionParams] = deriveDecoder
}

object OpenAIFunction {
  given Encoder[OpenAIFunction] = deriveEncoder
  given Decoder[OpenAIFunction] = deriveDecoder
}

object ToolDefinition {
  case object Command extends ToolDefinition {
    override val `type`: String = "function"
    override val function: OpenAIFunction = OpenAIFunction(
      name = "command_executor",
      description = "Execute linux shell commands inside the configured sandbox",
      parameters = OpenAIFunctionParams.Str(
        description = "JSON object containing a command field with the full command to execute"
      )
    )
  }

  case object Questionnaire extends ToolDefinition {
    override val `type`: String = "function"
    override val function: OpenAIFunction = OpenAIFunction(
      name = "questionnaire",
      description = "Present a questionnaire/question with several options to the user and receive their selection to proceed.",
      parameters = OpenAIFunctionParams.Custom(
        Json.obj(
          "type" -> Json.fromString("object"),
          "properties" -> Json.obj(
            "question" -> Json.obj(
              "type" -> Json.fromString("string"),
              "description" -> Json.fromString("The question or instruction to present to the user.")
            ),
            "options" -> Json.obj(
              "type" -> Json.fromString("array"),
              "items" -> Json.obj(
                "type" -> Json.fromString("string")
              ),
              "description" -> Json.fromString("List of answer options for the user to select from. Must not be empty.")
            )
          ),
          "required" -> Json.arr(Json.fromString("question"), Json.fromString("options"))
        )
      )
    )
  }

  case class Simple(
    `type`: String,
    function: OpenAIFunction
  ) extends ToolDefinition

  given Encoder[ToolDefinition] = Encoder.instance { tool =>
    Json.obj(
      "type" -> Json.fromString(tool.`type`),
      "function" -> tool.function.asJson
    )
  }

  given Decoder[ToolDefinition] = Decoder.instance { cursor =>
    for {
      t <- cursor.get[String]("type")
      f <- cursor.get[OpenAIFunction]("function")
    } yield Simple(t, f)
  }
}

object StreamOptions {
  given Encoder[StreamOptions] = deriveEncoder
  given Decoder[StreamOptions] = deriveDecoder
}

object OpenAIRequest {
  given Encoder[OpenAIRequest] = Encoder.instance { request =>
    val base = JsonObject(
      "model" -> request.model.asJson,
      "messages" -> request.messages.asJson
    )
    val withTools = if (request.tools.nonEmpty) base.add("tools", request.tools.asJson) else base
    val withStream = request.stream.fold(withTools)(stream => withTools.add("stream", stream.asJson))
    val withStreamOptions = request.stream_options.fold(withStream)(opts => withStream.add("stream_options", opts.asJson))
    Json.fromJsonObject(withStreamOptions)
  }
  given Decoder[OpenAIRequest] = deriveDecoder
}

object OpenAIUsage {
  given Encoder[OpenAIUsage] = deriveEncoder
  given Decoder[OpenAIUsage] = deriveDecoder
}

object ToolCallFunction {
  given Encoder[ToolCallFunction] = deriveEncoder
  given Decoder[ToolCallFunction] = deriveDecoder
}

object ToolCall {
  given Encoder[ToolCall] = deriveEncoder
  given Decoder[ToolCall] = deriveDecoder
}

object ToolResult {
  given Encoder[ToolResult] = deriveEncoder
  given Decoder[ToolResult] = deriveDecoder
}

object OpenAIResponseMessage {
  given Encoder[OpenAIResponseMessage] = deriveEncoder
  given Decoder[OpenAIResponseMessage] = deriveDecoder
}

object OpenAIResponseChoice {
  given Encoder[OpenAIResponseChoice] = deriveEncoder
  given Decoder[OpenAIResponseChoice] = deriveDecoder
}

object OpenAIMessage {
  given Encoder[OpenAIMessage] = Encoder.instance { msg =>
    val base = JsonObject("role" -> msg.role.asJson)
    val withContent = base.add("content", msg.content.fold("".asJson)(_.asJson))
    val withToolCalls = msg.tool_calls.fold(withContent)(calls => withContent.add("tool_calls", calls.asJson))
    val withToolCallId = msg.tool_call_id.fold(withToolCalls)(id => withToolCalls.add("tool_call_id", id.asJson))
    Json.fromJsonObject(withToolCallId)
  }

  given Decoder[OpenAIMessage] = deriveDecoder
}

object OpenAIResponse {
  given Encoder[OpenAIResponse] = deriveEncoder
  given Decoder[OpenAIResponse] = deriveDecoder
}
