package com.tark.adapters.sandbox.docker

import cats.effect.IO

import scala.sys.process.*

object DockerSandboxLifecycle {
  def ensureImageExists(imageName: String, forceBuild: Boolean): IO[Unit] = {
    val checkImage: IO[Boolean] = IO.blocking {
      try {
        Process(Seq("docker", "image", "inspect", imageName)).! == 0
      } catch {
        case _: Exception => false
      }
    }

    checkImage.flatMap { imageExists =>
      if (!imageExists || forceBuild) {
        IO.println(s"Building Docker sandbox image $imageName...") >>
          IO.blocking {
            Process(Seq("docker", "build", "-t", imageName, ".")).!!
          }.void.handleErrorWith { e =>
            IO.println(s"Warning: Docker build skipped/failed: ${e.getMessage}")
          }
      } else {
        IO.println(s"Docker sandbox image $imageName already exists. Skipping build.")
      }
    }
  }

  def start(sandbox: DockerSandbox): IO[Unit] = IO.blocking {
    try {
      Process(Seq("docker", "rm", "-f", sandbox.name)).!!
    } catch {
      case _: Exception => ""
    }

    try {
      Process(Seq(
        "docker", "run", "-d",
        "--name", sandbox.name,
        "-v", s"${sandbox.hostPath.toAbsolutePath}:${sandbox.containerPath}",
        "-w", sandbox.containerPath,
        sandbox.imageName,
        "tail", "-f", "/dev/null"
      )).!!
    } catch {
      case e: Exception =>
        throw new RuntimeException(
          s"Failed to start Docker sandbox container. Please ensure Docker is running, installed on your system, and the image '${sandbox.imageName}' is built successfully. Details: ${e.getMessage}",
          e
        )
    }
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
