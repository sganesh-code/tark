package com.tark.adapters.sandbox.docker

import cats.effect.IO
import com.tark.ports.outbound.sandbox.SandboxManager
import scala.sys.process.*

object DockerSandboxManager {
  /**
   * SandboxManager implementation for DockerSandbox under IO.
   */
  given SandboxManager[IO, DockerSandbox] with {
    override def start(sandbox: DockerSandbox): IO[Unit] = IO.blocking {
      try { 
        Process(Seq("docker", "rm", "-f", sandbox.name)).!! 
      } catch { 
        case _: Exception => "" 
      }

      val runCmd = Seq(
        "docker", "run", "-d",
        "--name", sandbox.name,
        "-v", s"${sandbox.hostPath.toAbsolutePath}:${sandbox.containerPath}",
        "-w", sandbox.containerPath,
        sandbox.imageName,
        "tail", "-f", "/dev/null"
      )
      Process(runCmd).!!
    }.void

    override def execute(sandbox: DockerSandbox, command: String): IO[String] = IO.blocking {
      val execCmd = Seq(
        "docker", "exec",
        sandbox.name,
        "sh", "-c", command
      )
      val stdout = new java.lang.StringBuilder
      val stderr = new java.lang.StringBuilder
      val logger = ProcessLogger(stdout.append(_).append("\n"), stderr.append(_).append("\n"))
      val exitCode = Process(execCmd).!(logger)
      
      if (exitCode == 0) {
        stdout.toString().trim
      } else {
        val err = stderr.toString().trim
        val out = stdout.toString().trim
        val errMsg = if (err.nonEmpty) err else if (out.nonEmpty) out else "Unknown error"
        throw new RuntimeException(s"Command failed with exit code $exitCode: $errMsg")
      }
    }

    override def stop(sandbox: DockerSandbox): IO[Unit] = IO.blocking {
      try {
        Process(Seq("docker", "stop", sandbox.name)).!!
        Process(Seq("docker", "rm", sandbox.name)).!!
      } catch {
        case _: Exception => ""
      }
    }.void
  }
}
