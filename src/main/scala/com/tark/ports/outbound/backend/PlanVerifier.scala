package com.tark.ports.outbound.backend

import com.tark.domain.GoalContract

/**
 * Outbound port for validating generated checklists against original goal contracts,
 * as defined in agent_harness_research_grounding.md.
 */
trait PlanVerifier[F[_]] {
  def verifyPlan(contract: GoalContract, plan: List[String]): F[Boolean]
}
