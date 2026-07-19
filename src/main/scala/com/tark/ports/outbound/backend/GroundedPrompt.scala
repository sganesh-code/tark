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

    val userEngagementInstruction =
      """## User Engagement & Interaction Guidelines
        |Always engage the user actively when you need clarification, feedback, or a decision on how to proceed.
        |Do not make major assumptions on behalf of the user when multiple viable options exist.
        |When you have questions or want to present choices for the user to select from to proceed, you MUST call the `questionnaire` tool call with the specific question/instruction and the options as answer choices. Never just list options in raw text if you can use the `questionnaire` tool call to get a structured selection from the user.""".stripMargin

    val allInstructions = totalEnrichment.systemInstructions :+ userEngagementInstruction

    val combinedSystemPrompt = Some(
      s"""You are a controlled, stateful autonomous coding agent.
         |Always execute actions systematically matching the goals, constraints, and instructions below.
         |
         |${allInstructions.mkString("\n\n")}
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
