package com.tark.application.backend

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.context.Context
import com.tark.domain.tool.{OpenAIMessage, ToolCall}
import com.tark.ports.outbound.backend.{LlmClient, Prompt}

class ContextDistiller[F[_]: Sync](llmClient: LlmClient[F]) {

  def distill(
    context: Context,
    toolCall: ToolCall,
    rawOutput: String
  ): F[String] = {
    val goal = context.memory.working.map(_.goal).filter(_.nonEmpty).getOrElse("achieving the task")
    val systemPrompt =
      """You are an expert Context Distillation Assistant.
        |The user is running an autonomous agent to achieve an overarching Goal.
        |A tool was executed and returned a very large result.
        |Your task is to analyze the raw tool output and extract ONLY the key information, data, findings, or facts that are directly relevant to achieving the Goal.
        |Filter out all irrelevant noise, verbose logs, boilerplate, or repetitive outputs.
        |Keep the distilled result extremely concise, factual, and clear.
        |
        |Do NOT introduce any conversational preamble, pleasantries, or introductory/concluding text.
        |Output ONLY the distilled findings.
        |""".stripMargin

    val userPrompt =
      s"""Goal: $goal
         |Tool: ${toolCall.function.name}
         |Arguments: ${toolCall.function.arguments}
         |
         |Raw Tool Output:
         |$rawOutput
         |
         |Distilled Output:""".stripMargin

    val prompt = Prompt(
      messages = List(
        OpenAIMessage(role = "system", content = Some(systemPrompt)),
        OpenAIMessage(role = "user", content = Some(userPrompt))
      ),
      availableTools = List.empty
    )

    llmClient.chat(prompt).map(_.content.trim)
  }
}
