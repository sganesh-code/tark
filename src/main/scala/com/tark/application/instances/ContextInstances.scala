package com.tark.application.instances

import com.tark.domain.{AgentState, Interaction}
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.tool.Tool
import com.tark.ports.shared.serialization.Serializable
import com.tark.ports.outbound.context.ContextOps
import com.tark.ports.shared.tool.ToolRegistry

object ContextInstances {
  given ContextOps[Context] with {

    override def getContextTools(context: Context): Map[String, Tool] =
      context.tools

    override def updateContext(context: Context, toolName: String, memoryValue: String): Context =
      val updatedMemory = context.memory + (toolName -> memoryValue)
      context.copy(memory = updatedMemory)

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

  given ToolRegistry[Context] with {
    override def register(context: Context, tool: Tool): Context =
      context.copy(tools = context.tools + (tool.name -> tool))

    override def lookup(context: Context, toolName: String): Option[Tool] =
      context.tools.get(toolName)
  }

  given Serializable[Context, String] with {
    override def serialize(context: Context): String = {
      val sb = new java.lang.StringBuilder()
      sb.append("# Session Context\n\n")

      sb.append("## Tools\n")
      if (context.tools.isEmpty) {
        sb.append("No tools registered.\n")
      } else {
        context.tools.foreach { case (name, tool) =>
          sb.append(s"- **$name**: Type = ${tool.toolType}\n")
        }
      }
      sb.append("\n")

      sb.append("## Memory\n")
      if (context.memory.isEmpty) {
        sb.append("No memory entries.\n")
      } else {
        if (context.memory.legacy.nonEmpty) {
          context.memory.legacy.foreach { case (key, value) =>
            sb.append(s"- **$key**: $value\n")
          }
        }
        if (context.memory.episodic.episodes.nonEmpty) {
          sb.append("### Episodic Memory\n")
          context.memory.episodic.episodes.foreach { ep =>
            sb.append(s"- **Session ${ep.sessionId}** (${ep.timestamp}): ${ep.summary}\n")
            if (ep.keyTakeaways.nonEmpty) {
              sb.append("  * Key Takeaways:\n")
              ep.keyTakeaways.foreach(kt => sb.append(s"    - $kt\n"))
            }
          }
        }
        if (context.memory.procedural.skills.nonEmpty) {
          sb.append("### Procedural Memory\n")
          context.memory.procedural.skills.foreach { skill =>
            sb.append(s"- **Skill: ${skill.name}**: ${skill.description}\n")
            if (skill.steps.nonEmpty) {
              skill.steps.foreach(step => sb.append(s"  * $step\n"))
            }
          }
        }
      }
      sb.append("\n")

      context.agentState.foreach { state =>
        sb.append("## Agent State\n")
        sb.append(s"- **Goal**: ${state.goal}\n")
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
        sb.append(s"- **Reason for Stop**: ${state.reasonForStop.getOrElse("None")}\n\n")
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
