package com.tark.adapters.sandbox.docker

import com.tark.domain.sandbox.Sandbox

import java.io.File
import java.nio.file.Path

case class DockerSandbox(
                        name: String,
                        imageName: String,
                        hostPath: Path,
                        containerPath: String = "/workspace"
                        ) extends Sandbox {
  override def buildProcess(command: String): (Seq[String], Option[File]) =
    (Seq("docker", "exec", name, "sh", "-c", command), None)
}
