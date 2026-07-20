package com.tark.ports.outbound.backend

import com.tark.domain.tool.OpenAIMessage

/**
 * Outbound port for dynamically evaluating whether the active plan step has been
 * completed during the current turn, as defined in agent_harness_research_grounding.md.
 */
trait ProgressTracker[F[_]] {
  def evaluateProgress(goal: String, activeStep: String, conversation: List[OpenAIMessage]): F[Boolean]
}
