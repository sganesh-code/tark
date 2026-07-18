package com.tark.ports

import com.tark.ui.AgentTask
import fs2.Stream

trait AgentBackend[F[_]]:
  def registerCompletions(update: List[String] => F[Unit]): F[Unit]
  def handleInput(input: String): Stream[F, AgentTask[F]]
