package com.tark.application.backend

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.Prompt
import com.tark.domain.context.Context
import com.tark.domain.tool.{OpenAIMessage, ToolCall}
import com.tark.ports.outbound.backend.LlmClient

class ContextDistiller[F[_]: Sync](llmClient: LlmClient[F]) {

  def distill(
    context: Context,
    toolCall: ToolCall,
    rawOutput: String
  ): F[String] = {
    val goal = context.memory.working.map(_.goal).filter(_.nonEmpty).getOrElse("achieving the task")
    val systemPrompt =
      """You are an expert Context Distillation Assistant specializing in raw-content preservation.
        |The user is running an autonomous agent to achieve an overarching Goal.
        |A tool was executed and returned a very large raw output (such as logs, files, outputs, or code).
        |Your task is to analyze the raw tool output and isolate the exact contiguous subsections, lines, or blocks that are highly relevant to achieving the Goal.
        |
        |CRITICAL RULES:
        |1. You MUST preserve the extracted relevant subsections EXACTLY as they appear in the original text. Do NOT modify, paraphrase, edit, summarize, explain, or translate any part of the extracted content.
        |2. For all irrelevant sections that you decide to remove, substitute them with a single-line placeholder: "... [REDACTED SECTION of X lines] ..." where X is your estimated count of original lines skipped.
        |3. Do NOT introduce any conversational preambles, pleasantries, explanations, introductory/concluding text, or markdown blocks (```). Output ONLY the finalized content-preserved text.
        |""".stripMargin

    val userPrompt =
      s"""Goal: $goal
         |Tool: ${toolCall.function.name}
         |Arguments: ${toolCall.function.arguments}
         |
         |Raw Tool Output:
         |$rawOutput
         |
         |Content-Preserved Distilled Output:""".stripMargin

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
