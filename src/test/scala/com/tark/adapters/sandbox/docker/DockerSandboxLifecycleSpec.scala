package com.tark.adapters.sandbox.docker

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import java.nio.file.Path

class DockerSandboxLifecycleSpec extends FunSuite {
  test("DockerSandboxLifecycle.start throws RuntimeException with helpful message on failure") {
    val sandbox = DockerSandbox(
      name = "invalid-sandbox-name-!!!",
      imageName = "non-existent-image-xyz",
      hostPath = Path.of(".")
    )

    val action = DockerSandboxLifecycle.start(sandbox)
    
    val ex = intercept[RuntimeException] {
      action.unsafeRunSync()
    }
    
    assert(ex.getMessage.contains("Failed to start Docker sandbox container"))
    assert(ex.getMessage.contains("Please ensure Docker is running"))
  }
}
