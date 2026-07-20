package com.tark.ports.outbound.backend

/**
 * Outbound port for decomposing established goals into sequential task plans,
 * as defined in agent_harness_research_grounding.md.
 */
trait TaskPlanner[F[_]] {
  def generatePlan(goal: String, deliverable: String, constraints: List[String]): F[List[String]]
}
