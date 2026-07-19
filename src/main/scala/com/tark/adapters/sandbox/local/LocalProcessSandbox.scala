package com.tark.adapters.sandbox.local

import com.tark.domain.sandbox.Sandbox

import java.io.File
import java.nio.file.Path

case class LocalProcessSandbox(
  name: String,
  allowedDirectory: Path
) extends Sandbox {
  override def buildProcess(command: String): (Seq[String], Option[File]) =
    (Seq("sh", "-c", command), Some(allowedDirectory.toFile))
}
