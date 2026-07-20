package com.tark.ports.outbound.backend

/**
 * Outbound port typeclass for decomposing established goal contracts into sequential task plans,
 * as defined in agent_harness_research_grounding.md.
 */
trait TaskPlanner[F[_], -G] {
  def generatePlan(contract: G): F[List[String]]
}
