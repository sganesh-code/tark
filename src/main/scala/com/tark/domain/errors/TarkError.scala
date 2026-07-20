package com.tark.domain.errors

/**
 * Foundational ADT for domain-specific exception hygiene in Tark,
 * as defined in agent_harness_research_grounding.md.
 */
sealed trait TarkError extends Exception {
  def message: String
  def cause: Option[Throwable]

  override def getMessage: String = message
  override def getCause: Throwable = cause.orNull
}

/**
 * Raised when the Goal Contract Intake phase fails to parse or extract a valid contract.
 */
case class IntakeError(message: String, cause: Option[Throwable] = None) extends TarkError

/**
 * Raised when the Task Checklist Planning phase fails to generate or parse execution steps.
 */
case class PlanningError(message: String, cause: Option[Throwable] = None) extends TarkError
