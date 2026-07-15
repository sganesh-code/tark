package com.tark.adapters.sandbox.local

import cats.effect.IO
import com.tark.domain.sandbox.Sandbox
import com.tark.ports.outbound.sandbox.SandboxManager

import java.nio.file.Path
import scala.sys.process.*

case class LocalProcessSandbox(
  name: String,
  allowedDirectory: Path
) extends Sandbox

object LocalProcessSandboxManager {
  given SandboxManager[IO, LocalProcessSandbox] with {
    override def start(sandbox: LocalProcessSandbox): IO[Unit] = IO.unit

    override def execute(sandbox: LocalProcessSandbox, command: String): IO[String] = IO.blocking {
      val commandWithDirectory = s"cd ${sandbox.allowedDirectory.toAbsolutePath} && $command"
      val stdout = new java.lang.StringBuilder
      val stderr = new java.lang.StringBuilder
      val logger = ProcessLogger(stdout.append(_).append("\n"), stderr.append(_).append("\n"))
      val exitCode = Process(Seq("sh", "-c", commandWithDirectory)).!(logger)

      if (exitCode == 0) {
        stdout.toString().trim
      } else {
        val err = stderr.toString().trim
        val out = stdout.toString().trim
        val errMsg = if (err.nonEmpty) err else if (out.nonEmpty) out else "Unknown error"
        throw new RuntimeException(s"Command failed with exit code $exitCode: $errMsg")
      }
    }

    override def stop(sandbox: LocalProcessSandbox): IO[Unit] = IO.unit
  }
}
