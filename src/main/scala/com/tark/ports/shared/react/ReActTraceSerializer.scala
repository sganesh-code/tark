package com.tark.ports.shared.react

import com.tark.domain.react.{CallTool, Finish, ReActState}

object ReActTraceSerializer {

  /**
   * Purely functional serializer that translates a ReActState into a beautifully formatted Markdown trace document.
   */
  def serialize(state: ReActState): String = {
    val sb = new StringBuilder()
    sb.append(s"# ReAct Execution Trace\n")
    sb.append(s"**Goal:** ${state.goal}\n")
    sb.append(s"**Status:** ${if (state.done) "Completed" else "In Progress"}\n")
    sb.append(s"**Termination Reason:** ${state.reasonForStop.getOrElse("N/A")}\n")
    sb.append(s"**Total Steps:** ${state.steps.size} / ${state.maxSteps}\n\n")
    sb.append(s"## Steps History\n\n")

    state.steps.zipWithIndex.foreach { case (step, idx) =>
      sb.append(s"### Step ${idx + 1}\n")
      sb.append(s"- **Thought:** ${step.thought}\n")
      
      val actionStr = step.action match {
        case CallTool(name, args) => s"Call Tool `$name` with arguments:\n```json\n${args.spaces2}\n```"
        case Finish(output)       => s"Finish with answer:\n```\n$output\n```"
      }
      sb.append(s"- **Action:** $actionStr\n")
      
      val obsStr = step.observation.getOrElse("No observation available.")
      sb.append(s"- **Observation:**\n```\n$obsStr\n```\n\n")
    }
    
    sb.toString()
  }
}
