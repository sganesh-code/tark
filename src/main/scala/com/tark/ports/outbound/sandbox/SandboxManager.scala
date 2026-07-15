package com.tark.ports.outbound.sandbox

import com.tark.domain.sandbox.Sandbox

trait SandboxManager[F[_], S <: Sandbox] {
  def start(sandbox: S): F[Unit]
  def execute(sandbox: S, command: String): F[String]
  def stop(sandbox: S): F[Unit]
}
