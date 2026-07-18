package com.tark.ports.outbound.backend

import com.tark.domain.AgentState
import com.tark.domain.memory.{EpisodicMemory, Memory, ProceduralMemory}
import com.tark.ports.outbound.memory.MemoryOps

trait ContextProvider[L, -Q]:
  def provide(layer: L, query: Q): PromptEnrichment
  
object ContextProvider {
  given ContextProvider[Option[AgentState], Unit] with {
    override def provide(layer: Option[AgentState], query: Unit): PromptEnrichment =
      layer.map { state => 
        val statusBlock = List(
          Option.when(state.goal.nonEmpty) (s"- **Active Goal**: ${state.goal}"),
          Option.when(state.deliverable.nonEmpty)(s"- **Deliverable**: ${state.deliverable}"),
          Option.when(state.constraints.nonEmpty)(s"- **Constraints**: ${state.constraints.mkString(", ")}"),
          Option.when(state.plan.nonEmpty) {
            val planStr = state.plan.zipWithIndex.map {
              case (step, idx) => 
                val marker = if (idx == state.currentStep) "-> " else "  "
                s" $marker${idx + 1}. $step"
            }.mkString("\n")
            s"- **Plan Progress**:\n$planStr"
          }
        ).flatten
        
        if (statusBlock.isEmpty)
          PromptEnrichment()
        else {
          PromptEnrichment(
            systemInstructions =  List(s"## Current Agent\n${statusBlock.mkString("\n")}"),
            injectedMessages = state.messages
          )
        }
      }.getOrElse(PromptEnrichment())
  }
  
  given ContextProvider[EpisodicMemory, String] with {
    override def provide(layer: EpisodicMemory, query: String): PromptEnrichment = {
      val relevant = MemoryOps.retrieveRelevantEpisodes(
        memory = Memory(episodic = layer),
        goal = query,
        maxEntries = 2
      )
      
      if (relevant.isEmpty)
        PromptEnrichment()
      else {
        val episodesStr = relevant.map { ep => 
          val takeawaysStr = ep.keyTakeaways.map(t => s"  * Takeaway: $t").mkString("\n")
          s"- **Session ${ep.sessionId}**:\n Summary: ${ep.summary}\n$takeawaysStr"
        }.mkString("\n\n")
        
        PromptEnrichment(
          systemInstructions = List(s"## Relevant Lessons From Past Sessions\n$episodesStr")
        )
      }
    }
  }
  
  given ContextProvider[ProceduralMemory, String] with {
    override def provide(layer: ProceduralMemory, query: String): PromptEnrichment = {
      val matchedSkills = layer.skills.filter(s => query.toLowerCase.contains(s.name.toLowerCase))
      if (matchedSkills.isEmpty)
        PromptEnrichment()
      else {
        val skillsStr = matchedSkills.map { s =>
          s"- **Procedure [${s.name}]**: ${s.description}\n  Steps:\n" + s.steps.map(step => s"    * $step").mkString("\n")
        }.mkString("\n\n")
        PromptEnrichment(
          systemInstructions = List(s"## Operational Workflow Guidelines\n$skillsStr")
        )
      }
    }
  }
}
