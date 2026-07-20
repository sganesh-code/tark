package com.tark.ports.outbound.backend

import com.tark.domain.ProgressContext
import com.tark.ports.shared.serialization.Deserializable

object ProgressTrackerPrompt {

  def systemInstructions: String = {
    """You are an objective Progress Tracker. Your job is to analyze the conversation messages of the current turn and evaluate if the Active Step has been fully completed.
      |
      |Analyze the conversations (tool results, file read content, command outputs, and final response) to check if the exact tasks required by the Active Step are complete.
      |
      |You MUST return your response as a single, valid JSON object with the following schema:
      |{
      |  "completed": true,
      |  "reason": "explanation of why it is completed or what is remaining"
      |}
      |
      |If the conversations confirm that the active step has been fully achieved, set "completed" to true.
      |If the active step is still in progress, partially complete, or failed, set "completed" to false.
      |
      |Your response MUST be valid JSON only. Do not output any XML tags, markdown blocks (like ```json), preambles, introductory or concluding text.
      |""".stripMargin
  }

  def userPrompt(context: ProgressContext): String = {
    val convStr = context.conversation.flatMap { msg =>
      msg.content.map(content => s"[${msg.role}] $content")
    }.mkString("\n\n")

    s"""Goal: ${context.goal}
       |Active Step to evaluate: ${context.activeStep}
       |
       |Turn Conversation History:
       |$convStr
       |
       |Please execute the step progress evaluation now.
       |""".stripMargin
  }

  // Provide a given instance of Deserializable to parse the validation result into a Boolean cleanly
  given Deserializable[String, Boolean] with {
    override def deserialize(data: String): Either[Throwable, Boolean] = {
      val trimmed = data.trim.toLowerCase
      if (trimmed.contains("true") || trimmed.contains("\"completed\": true") || trimmed.contains("\"completed\":true")) {
        Right(true)
      } else {
        Right(false)
      }
    }
  }
}
