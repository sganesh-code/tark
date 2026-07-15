package com.tark.ports.shared.ui

import com.tark.domain.Interaction
import com.tark.domain.react.{CallTool, Finish, ReActState, ReActStep}
import com.tark.ports.shared.tool.TextFormatter

object ReActToUiMapper {
  def toStepMessages(steps: List[ReActStep]): List[Message] = {
    steps.flatMap { step =>
      val actionMsg = step.action match {
        case CallTool(name, args) =>
          Message.AI(s"Requested tool '$name' with args: ${args.noSpaces}")
        case Finish(output) =>
          Message.AI(TextFormatter.cleanLlmOutput(output))
      }
      val obsMsg = step.observation.map(obs => Message.System(s"[EXECUTING] -> ${TextFormatter.limitToolOutput(obs, 15)}"))
      List(actionMsg) ++ obsMsg.toList
    }
  }

  def toInteractions(steps: List[ReActStep], originalInput: String, startedAtMillis: Long): List[Interaction] = {
    steps.zipWithIndex.map { case (step, idx) =>
      val now = startedAtMillis + idx
      val inputMsg = step.action match {
        case CallTool(name, args) => s"[AI Tool Call: $name with args: ${args.noSpaces}]"
        case Finish(_) => originalInput
      }
      val outputMsg = step.observation.getOrElse("No observation")
      val toolName = step.action match {
        case CallTool(name, _) => name
        case Finish(_) => "llm_completion"
      }
      Interaction(
        id = s"interaction_${now}",
        input = inputMsg,
        output = outputMsg,
        timestamp = now,
        toolName = toolName
      )
    }
  }

  def toAgentState(reactState: ReActState): com.tark.domain.AgentState = {
    com.tark.domain.AgentState(
      goal = reactState.goal,
      done = reactState.done,
      reasonForStop = reactState.reasonForStop,
      plan = reactState.steps.map(_.thought).toList,
      toolResults = reactState.steps.flatMap(_.observation).toList,
      completedSteps = reactState.steps.map(s => s"Action: ${s.action}").toList
    )
  }
}
