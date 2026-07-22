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

case class ToolDefinition(
  `type`: String,
  function: OpenAIFunction
)

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
  given Encoder[ToolDefinition] = deriveEncoder
  given Decoder[ToolDefinition] = deriveDecoder
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
