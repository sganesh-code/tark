package com.tark.domain.tool

import com.tark.domain.tool.{FunctionDefinition, FunctionParameters, FunctionProperty, ToolDefinition}
import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}

case class FunctionProperty(
  `type`: String,
  description: String
)
object FunctionProperty {
  given Encoder[FunctionProperty] = deriveEncoder
  given Decoder[FunctionProperty] = deriveDecoder
}

case class FunctionParameters(
  `type`: String = "object",
  properties: Map[String, FunctionProperty],
  required: List[String]
)
object FunctionParameters {
  given Encoder[FunctionParameters] = deriveEncoder
  given Decoder[FunctionParameters] = deriveDecoder
}

case class FunctionDefinition(
  name: String,
  description: String,
  parameters: FunctionParameters
)
object FunctionDefinition {
  given Encoder[FunctionDefinition] = deriveEncoder
  given Decoder[FunctionDefinition] = deriveDecoder
}

case class ToolDefinition(
  `type`: String = "function",
  function: FunctionDefinition
)
object ToolDefinition {
  given Encoder[ToolDefinition] = deriveEncoder
  given Decoder[ToolDefinition] = deriveDecoder
}
