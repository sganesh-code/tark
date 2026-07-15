package com.tark.ports.outbound.react

import com.tark.domain.react.ReActState

/**
 * Typeclass defining the Executor interface.
 * Takes a goal and returns an execution result within a functional effect F[_].
 */
trait ReActExecutor[E, F[_]] {
  def execute(executor: E, goal: String): F[ReActState]
}
