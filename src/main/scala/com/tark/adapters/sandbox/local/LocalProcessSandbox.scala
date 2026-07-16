package com.tark.adapters.sandbox.local

import com.tark.domain.sandbox.Sandbox

import java.nio.file.Path

case class LocalProcessSandbox(
  name: String,
  allowedDirectory: Path
) extends Sandbox
