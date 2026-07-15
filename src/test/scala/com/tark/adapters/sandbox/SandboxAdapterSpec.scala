package com.tark.adapters.sandbox

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.adapters.sandbox.docker.DockerSandbox
import com.tark.adapters.sandbox.local.LocalProcessSandbox
import com.tark.domain.sandbox.Sandbox
import com.tark.ports.outbound.sandbox.SandboxManager
import munit.FunSuite

import java.nio.file.Paths

class SandboxAdapterSpec extends FunSuite {
  test("Sandbox adapter models are correctly structured") {
    val docker: Sandbox = DockerSandbox("tark-box", "alpine:latest", Paths.get("."))
    assertEquals(docker.name, "tark-box")

    val local: Sandbox = LocalProcessSandbox("local-box", Paths.get("."))
    assertEquals(local.name, "local-box")
  }

  test("SandboxManager: supports custom implementations and executes commands") {
    val sandbox = LocalProcessSandbox("tark-stub-box", Paths.get("."))
    val stubManager = new SandboxManager[IO, LocalProcessSandbox] {
      override def start(s: LocalProcessSandbox): IO[Unit] = IO.unit
      override def execute(s: LocalProcessSandbox, cmd: String): IO[String] = IO.pure(s"Stubbed execution: $cmd")
      override def stop(s: LocalProcessSandbox): IO[Unit] = IO.unit
    }

    val result = stubManager.execute(sandbox, "whoami").unsafeRunSync()
    assertEquals(result, "Stubbed execution: whoami")
  }
}
