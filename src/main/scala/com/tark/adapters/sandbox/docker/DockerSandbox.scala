package com.tark.adapters.sandbox.docker

import com.tark.domain.sandbox.Sandbox

import java.nio.file.Path

case class DockerSandbox(
                        name: String,
                        imageName: String,
                        hostPath: Path,
                        containerPath: String = "/workspace"
                        ) extends Sandbox
