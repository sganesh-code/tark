package com.tark.ports.shared.react

import com.tark.domain.*
import com.tark.domain.memory.EpisodeSummary
import com.tark.domain.react.{CallTool, Finish, ReActState, ReActStep}
import com.tark.domain.tool.Tool
import com.tark.ports.shared.tool.ToToolDescription
import io.circe.syntax.*

object ReActPrompt {

  def formatTools(tools: List[Tool]): String = {
    val descOps = summon[ToToolDescription[Tool]]
    tools.map { t =>
      val definition = descOps.describe(t)
      val name = definition.function.name
      val desc = definition.function.description
      val schemaJson = definition.function.parameters.asJson.spaces2
      s"- **$name**: $desc\n  Schema:\n  $schemaJson"
    }.mkString("\n\n")
  }

  def formatHistory(steps: List[ReActStep]): String = {
    steps.map { step =>
      val actionStr = step.action match {
        case CallTool(name, input) => s"Action: $name ${input.noSpaces}"
        case Finish(output)        => s"Finish: $output"
      }
      val obsStr = step.observation.map(obs => s"\nObservation: $obs").getOrElse("")
      s"Thought: ${step.thought}\n$actionStr$obsStr"
    }.mkString("\n\n")
  }

  def systemPrompt(tools: List[Tool]): String = {
    s"""You are a helpful assistant executing a task. You follow the ReAct pattern: Thought -> Action -> Observation.
       |You solve the task step-by-step.
       |
       |CRITICAL RULES:
       |1. You MUST NOT simulate, fabricate, or hallucinate tool executions or observations in your response.
       |2. You MUST obtain real observations by calling the available tools (like `command_executor`) to inspect files, execute commands, and explore the environment.
       |3. Do NOT call the `conclude_task` tool on your first step or prematurely. You are strictly forbidden from calling `conclude_task` until you have gathered real observations and evidence from the system to support your final answer.
       |
       |You MUST return your response as a single, valid JSON object with the following schema:
       |{
       |  "thought": "your step-by-step reasoning for this step explaining why you are calling the tool",
       |  "tool": "the name of the tool you want to call",
       |  "arguments": {
       |     "argument_name": "argument_value"
       |  }
       |}
       |
       |When you have successfully gathered real observations and are ready to conclude the task, you MUST call the synthetic "conclude_task" tool with the following schema:
       |{
       |  "thought": "your reasoning explaining why the task is finished based on real observations",
       |  "tool": "conclude_task",
       |  "arguments": {
       |     "final_answer": "the final complete answer or outcome to present to the user"
       |  }
       |}
       |
       |Do NOT include any markdown code blocks, preambles, or conversational text. Your response MUST be valid JSON only conforming strictly to this schema.
       |
       |Available Tools:
       |${formatTools(tools)}
       |""".stripMargin
  }

  def userPrompt(state: ReActState, relevantEpisodes: List[EpisodeSummary] = List.empty): String = {
    val memoryContextStr = if (relevantEpisodes.isEmpty) "" else {
      val formattedEpisodes = relevantEpisodes.map { ep =>
        val takeawaysStr = if (ep.keyTakeaways.isEmpty) "" else ep.keyTakeaways.map(t => s"  - $t").mkString("\n", "\n", "")
        s"- Prior Run (${ep.sessionId}): ${ep.summary}$takeawaysStr"
      }.mkString("\n")
      s"""## Context from Prior Sessions:
         |$formattedEpisodes
         |
         |""".stripMargin
    }

    val historyStr = if (state.steps.isEmpty) "" else formatHistory(state.steps) + "\n\n"
    s"""${memoryContextStr}Goal: ${state.goal}
       |
       |${historyStr}Thought:"""
  }
}
