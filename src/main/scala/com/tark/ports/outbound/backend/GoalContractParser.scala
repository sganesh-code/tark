package com.tark.ports.outbound.backend

import com.tark.domain.GoalContract

/**
 * Outbound port for extracting a structured GoalContract from unstructured user prompt,
 * as defined in agent_harness_research_grounding.md.
 */
trait GoalContractParser[F[_]] {
  def parseGoal(input: String): F[GoalContract]
}
