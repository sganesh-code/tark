package com.tark.adapters.backend.ollama

import cats.effect.IO
import com.tark.domain.Interaction
import com.tark.domain.tool.ToolDefinition
import com.tark.ports.outbound.backend.{SystemPrompt, ToMessages, UserPrompt}
import io.circe.*
import io.circe.generic.semiauto.*
import io.circe.syntax.*

case class OllamaFunctionCall(name: String, arguments: String)
object OllamaFunctionCall {
  given Encoder[OllamaFunctionCall] = deriveEncoder
  given Decoder[OllamaFunctionCall] = deriveDecoder
}

case class OllamaToolCall(
  id: String,
  `type`: String = "function",
  function: OllamaFunctionCall
)
object OllamaToolCall {
  given Encoder[OllamaToolCall] = deriveEncoder
  given Decoder[OllamaToolCall] = deriveDecoder
}

case class OllamaMessage(
  role: String,
  content: Option[String],
  tool_calls: Option[List[OllamaToolCall]] = None,
  tool_call_id: Option[String] = None,
  name: Option[String] = None
)
object OllamaMessage {
  given Encoder[OllamaMessage] = deriveEncoder
  given Decoder[OllamaMessage] = deriveDecoder
}

case class ResponseFormat(`type`: String)
object ResponseFormat {
  given Encoder[ResponseFormat] = deriveEncoder
  given Decoder[ResponseFormat] = deriveDecoder
}

case class OllamaRequest(
  model: String,
  messages: List[OllamaMessage],
  tools: Option[List[ToolDefinition]] = None,
  stream: Boolean = false,
  format: Option[String] = None,
  tool_choice: Option[String] = None,
  response_format: Option[ResponseFormat] = None
)
object OllamaRequest {
  given Encoder[OllamaRequest] = deriveEncoder
  given Decoder[OllamaRequest] = deriveDecoder
}

case class OllamaChoice(message: OllamaMessage)
object OllamaChoice {
  given Encoder[OllamaChoice] = deriveEncoder
  given Decoder[OllamaChoice] = deriveDecoder
}

case class OllamaResponse(choices: List[OllamaChoice])
object OllamaResponse {
  given Encoder[OllamaResponse] = deriveEncoder
  given Decoder[OllamaResponse] = deriveDecoder
}

object OllamaProtocol {

  given ToMessages[SystemPrompt, OllamaMessage] with {
    override def toMessages(sys: SystemPrompt): List[OllamaMessage] =
      List(OllamaMessage("system", Some(sys.content)))
  }

  given ToMessages[UserPrompt, OllamaMessage] with {
    override def toMessages(user: UserPrompt): List[OllamaMessage] =
      List(OllamaMessage("user", Some(user.content)))
  }

  given ToMessages[Interaction, OllamaMessage] with {
    override def toMessages(interaction: Interaction): List[OllamaMessage] = {
      if (interaction.toolName == "llm_completion") {
        List(
          OllamaMessage("user", Some(interaction.input)),
          OllamaMessage("assistant", Some(interaction.output))
        )
      } else {
        val callId = s"call_${interaction.id}"
        val commandVal = if (interaction.input.contains("command -> ")) {
          val startIdx = interaction.input.indexOf("command -> ") + 11
          val endIdx = interaction.input.lastIndexOf(")")
          if (endIdx > startIdx) interaction.input.substring(startIdx, endIdx).trim else "ls"
        } else if (interaction.input.contains("command='")) {
          val startIdx = interaction.input.indexOf("command='") + 9
          val endIdx = interaction.input.lastIndexOf("'")
          if (endIdx > startIdx) interaction.input.substring(startIdx, endIdx).trim else "ls"
        } else {
          "ls"
        }
        
        val toolArgsJson = s"""{"command": ${commandVal.asJson.noSpaces}}"""
        
        List(
          OllamaMessage(
            role = "assistant",
            content = Some(s"""{"thought": "Executing tool call...", "action": {"name": "${interaction.toolName}", "arguments": $toolArgsJson}}"""),
            tool_calls = None
          ),
          OllamaMessage(
            role = "tool",
            content = Some(interaction.output),
            tool_call_id = Some(callId),
            name = Some(interaction.toolName)
          )
        )
      }
    }
  }
}
