package com.tark.ports.outbound.backend

/**
 * Outbound port typeclass for validating proposed plans against goal contracts,
 * as defined in agent_harness_research_grounding.md.
 */
trait PlanVerifier[F[_], -G] {
  def verifyPlan(contract: G, plan: List[String]): F[Boolean]
}
