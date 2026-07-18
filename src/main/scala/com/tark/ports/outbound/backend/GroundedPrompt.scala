package com.tark.ports.outbound.backend

import cats.implicits.catsSyntaxSemigroup
import com.tark.domain.AgentState
import com.tark.domain.memory.{EpisodicMemory, Memory, ProceduralMemory}
import com.tark.domain.tool.OpenAIMessage

object GroundedPrompt {
  def compile(
             basePrompt: Prompt,
             memory: Memory,
             goalQuery: String
             ) (using
                workingProv: ContextProvider[Option[AgentState], Unit],
                episodicProv: ContextProvider[EpisodicMemory, String],
                proceduralProv: ContextProvider[ProceduralMemory, String]
             ): Prompt = {

    val workingEnrichment = workingProv.provide(memory.working, ())
    val episodicEnrichment = episodicProv.provide(memory.episodic, goalQuery)
    val proceduralEnrichment = proceduralProv.provide(memory.procedural, goalQuery)

    val totalEnrichment = workingEnrichment |+| episodicEnrichment |+| proceduralEnrichment

    val combinedSystemPrompt =
      if (totalEnrichment.systemInstructions.isEmpty)
        None
      else Some(
        s"""You are a controlled, stateful autonomous coding agent.
           |Always execute actions systematically matching the goals, constraints, and instructions below.
           |
           |${totalEnrichment.systemInstructions.mkString("\n\n")}
           |""".stripMargin
      )

    val systemMessage = combinedSystemPrompt.map(content => OpenAIMessage(role = "system", content = Some(content))).toList

    val enrichedMessages = systemMessage ++ basePrompt.messages ++ totalEnrichment.injectedMessages
    val enrichedTools = basePrompt.availableTools ++ totalEnrichment.additionalTools

    Prompt(
      messages = enrichedMessages,
      availableTools = enrichedTools.distinctBy(_.function.name)
    )
  }
}
