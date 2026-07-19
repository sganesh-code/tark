package com.tark.domain.memory

import com.tark.domain.*
import io.circe.generic.semiauto.*
import io.circe.{Decoder, Encoder}

/**
 * Represents the unified memory layer of an LLM agent, containing
 * Working (current run context), Episodic (summaries of prior runs),
 * Procedural (capabilities/skills/workflows), and Semantic (knowledge base placeholders) memories.
 */
case class Memory(
                 working: Option[AgentState] = None,
                 episodic: EpisodicMemory = EpisodicMemory(),
                 procedural: ProceduralMemory = ProceduralMemory(),
                 semantic: Option[SemanticMemory] = None
                 ) {

  def isEmpty: Boolean =
    working.isEmpty && episodic.episodes.isEmpty && procedural.skills.isEmpty && semantic.isEmpty
}

object Memory {
  given Encoder[Memory] = deriveEncoder
  given Decoder[Memory] = deriveDecoder
}

/**
 * Episodic Memory represents a collection of summaries of prior sessions or runs.
 */
case class EpisodicMemory(
                         episodes: List[EpisodeSummary] = List.empty
                         )

object EpisodicMemory {
  given Encoder[EpisodicMemory] = deriveEncoder
  given Decoder[EpisodicMemory] = deriveDecoder
}

/**
 * A distilled summary of an individual session or prior conversation.
 */
case class EpisodeSummary(
                         sessionId: String,
                         timestamp: Long,
                         summary: String,
                         keyTakeaways: List[String] = List.empty
                         )

object EpisodeSummary {
  given Encoder[EpisodeSummary] = deriveEncoder
  given Decoder[EpisodeSummary] = deriveDecoder
}

/**
 * Procedural Memory represents tools, skills, or structured workflows.
 */
case class ProceduralMemory(
                           skills: List[Skill] = List.empty
                           )

object ProceduralMemory {
  given Encoder[ProceduralMemory] = deriveEncoder
  given Decoder[ProceduralMemory] = deriveDecoder
}

/**
 * Represents a structured skill or workflow pattern.
 */
case class Skill(
                name: String,
                description: String,
                steps: List[String] = List.empty
                )

object Skill {
  given Encoder[Skill] = deriveEncoder
  given Decoder[Skill] = deriveDecoder
}

/**
 * Semantic Memory represents documents, facts, or embeddings (placeholder structure).
 */
case class SemanticMemory(
                         entries: List[String] = List.empty
                         )

object SemanticMemory {
  given Encoder[SemanticMemory] = deriveEncoder
  given Decoder[SemanticMemory] = deriveDecoder
}
