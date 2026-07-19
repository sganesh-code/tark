package com.tark.application.instances

import com.tark.domain.{AgentState, Interaction}
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.tool.ToolDefinition
import com.tark.ports.shared.serialization.Serializable
import com.tark.ports.outbound.context.ContextOps

object ContextInstances {
  given ContextOps[Context] with {

    override def getContextTools(context: Context): List[ToolDefinition] =
      context.tools

    override def getContextHistory(context: Context): List[Interaction] =
      context.history

    override def addInteraction(context: Context, interaction: Interaction): Context =
      context.copy(history = context.history :+ interaction)

    override def getAgentState(context: Context): Option[AgentState] =
      context.agentState

    override def updateAgentState(context: Context, f: AgentState => AgentState): Context =
      context.updateAgentState(f)

    override def getMemory(context: Context): Memory =
      context.memory

    override def updateMemory(context: Context, f: Memory => Memory): Context =
      context.copy(memory = f(context.memory))
  }

  given Serializable[Memory, String] with {
    override def serialize(memory: Memory): String = {
      val sb = new java.lang.StringBuilder()
      if (memory.isEmpty) {
        sb.append("No memory entries.\n")
      } else {
        if (memory.episodic.episodes.nonEmpty) {
          sb.append("### Episodic Memory\n")
          memory.episodic.episodes.foreach { ep =>
            sb.append(s"- **Session ${ep.sessionId}** (${ep.timestamp}): ${ep.summary}\n")
            if (ep.keyTakeaways.nonEmpty) {
              sb.append("  * Key Takeaways:\n")
              ep.keyTakeaways.foreach(kt => sb.append(s"    - $kt\n"))
            }
          }
        }
        if (memory.procedural.skills.nonEmpty) {
          sb.append("### Procedural Memory\n")
          memory.procedural.skills.foreach { skill =>
            sb.append(s"- **Skill: ${skill.name}**: ${skill.description}\n")
            if (skill.steps.nonEmpty) {
              skill.steps.foreach(step => sb.append(s"  * $step\n"))
            }
          }
        }
      }
      sb.toString
    }
  }

  given Serializable[AgentState, String] with {
    override def serialize(state: AgentState): String = {
      val sb = new java.lang.StringBuilder()
      sb.append("- **Goal**: " + state.goal + "\n")
      sb.append(s"- **Deliverable**: ${state.deliverable}\n")

      sb.append("- **Constraints**:\n")
      if (state.constraints.isEmpty) sb.append("  - None\n")
      else state.constraints.foreach(c => sb.append(s"  - $c\n"))

      sb.append("- **Assumptions**:\n")
      if (state.assumptions.isEmpty) sb.append("  - None\n")
      else state.assumptions.foreach(a => sb.append(s"  - $a\n"))

      sb.append("- **Known Facts**:\n")
      if (state.knownFacts.isEmpty) sb.append("  - None\n")
      else state.knownFacts.foreach(f => sb.append(s"  - $f\n"))

      sb.append("- **Open Questions**:\n")
      if (state.openQuestions.isEmpty) sb.append("  - None\n")
      else state.openQuestions.foreach(q => sb.append(s"  - $q\n"))

      sb.append("- **Plan**:\n")
      if (state.plan.isEmpty) sb.append("  - No steps defined\n")
      else state.plan.zipWithIndex.foreach { case (step, idx) =>
        val marker = if (idx == state.currentStep) "-> " else "  "
        sb.append(s"  $marker${idx + 1}. $step\n")
      }

      sb.append("- **Completed Steps**:\n")
      if (state.completedSteps.isEmpty) sb.append("  - None\n")
      else state.completedSteps.foreach(step => sb.append(s"  - $step\n"))

      sb.append("- **Tool Results**:\n")
      if (state.toolResults.isEmpty) sb.append("  - None\n")
      else state.toolResults.foreach(r => sb.append(s"  - $r\n"))

      sb.append(s"- **Candidate Answer**: ${state.candidateAnswer.getOrElse("None")}\n")
      sb.append(s"- **Confidence**: ${state.confidence}\n")
      sb.append(s"- **Done**: ${state.done}\n")
      sb.append(s"- **Reason for Stop**: ${state.reasonForStop.getOrElse("None")}\n")
      sb.toString
    }
  }

  given Serializable[Context, String] with {
    override def serialize(context: Context): String = {
      val sb = new java.lang.StringBuilder()
      sb.append("# Session Context\n\n")

      sb.append("## Tools\n")
      if (context.tools.isEmpty) {
        sb.append("No tools registered.\n")
      } else {
        context.tools.foreach { tool =>
          sb.append(s"- **${tool.function.name}**: ${tool.function.description}\n")
        }
      }
      sb.append("\n")

      sb.append("## Memory\n")
      sb.append(summon[Serializable[Memory, String]].serialize(context.memory))
      sb.append("\n")

      context.agentState.foreach { state =>
        sb.append("## Agent State\n")
        sb.append(summon[Serializable[AgentState, String]].serialize(state))
        sb.append("\n")
      }

      sb.append("## History\n")
      if (context.history.isEmpty) {
        sb.append("No interactions in history.\n")
      } else {
        context.history.zipWithIndex.foreach { case (interaction, idx) =>
          sb.append(s"### Interaction ${idx + 1} (${interaction.id})\n")
          sb.append(s"- **Timestamp**: ${interaction.timestamp}\n")
          sb.append(s"- **Tool**: ${interaction.toolName}\n")
          sb.append(s"- **Input**: `${interaction.input}`\n")
          sb.append(s"- **Output**: `${interaction.output}`\n\n")
        }
      }

      import io.circe.syntax.*
      sb.append("\n<!-- MEMORY_JSON\n")
      sb.append(context.memory.asJson.noSpaces)
      sb.append("\n-->\n")

      sb.toString
    }
  }
}
