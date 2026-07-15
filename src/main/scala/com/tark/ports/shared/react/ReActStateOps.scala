package com.tark.ports.shared.react

import com.tark.domain.react.{ReActState, ReActStep}
import com.tark.ports.shared.react.ReActStateOps

/**
 * Typeclass defining pure functional operations on the ReAct state.
 */
trait ReActStateOps[S] {
  def addStep(state: S, step: ReActStep): S
  def updateLastObservation(state: S, obs: String): S
  def markDone(state: S, reason: String): S
  def isBudgetExceeded(state: S): Boolean
}

object ReActStateOps {
  given ReActStateOps[ReActState] with {
    override def addStep(state: ReActState, step: ReActStep): ReActState =
      state.copy(steps = state.steps :+ step)

    override def updateLastObservation(state: ReActState, obs: String): ReActState = {
      state.steps.lastOption match {
        case Some(lastStep) =>
          state.copy(steps = state.steps.init :+ lastStep.copy(observation = Some(obs)))
        case None =>
          state
      }
    }

    override def markDone(state: ReActState, reason: String): ReActState =
      state.copy(done = true, reasonForStop = Some(reason))

    override def isBudgetExceeded(state: ReActState): Boolean =
      state.steps.size >= state.maxSteps
  }
}
