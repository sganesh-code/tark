package com.tark.domain

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

/**
 * Represents a structured goal contract extracted from user input during the intake phase,
 * as described in agent_harness_research_grounding.md.
 */
case class GoalContract(
  goal: String,
  deliverable: String,
  constraints: List[String],
  assumptions: List[String],
  knownFacts: List[String]
)

object GoalContract {
  given Encoder[GoalContract] = deriveEncoder
  given Decoder[GoalContract] = deriveDecoder
}
