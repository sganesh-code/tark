package com.tark.adapters.sandbox.docker

import cats.effect.IO

import scala.sys.process.*

object DockerSandboxLifecycle {
  def start(sandbox: DockerSandbox): IO[Unit] = IO.blocking {
    try {
      Process(Seq("docker", "rm", "-f", sandbox.name)).!!
    } catch {
      case _: Exception => ""
    }

    Process(Seq(
      "docker", "run", "-d",
      "--name", sandbox.name,
      "-v", s"${sandbox.hostPath.toAbsolutePath}:${sandbox.containerPath}",
      "-w", sandbox.containerPath,
      sandbox.imageName,
      "tail", "-f", "/dev/null"
    )).!!
  }.void

  def stop(sandbox: DockerSandbox): IO[Unit] = IO.blocking {
    try {
      Process(Seq("docker", "stop", sandbox.name)).!!
      Process(Seq("docker", "rm", sandbox.name)).!!
    } catch {
      case _: Exception => ""
    }
  }.void
}
