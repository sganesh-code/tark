package com.tark.bootstrap

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.bootstrap.OllamaRuntime.given
import com.tark.domain.Config
import com.tark.ports.outbound.backend.{BackendProvider, LlmClient}
import munit.FunSuite

class OllamaRuntimeSpec extends FunSuite {
  test("BackendProvider: successfully allocates and releases client") {
    given RuntimeConfig = RuntimeConfig(Config.default, forceBuild = false)
    val provider = summon[BackendProvider[IO]]
    val ((client, _), release) = provider.getClients.allocated.unsafeRunSync()

    try {
      assert(client != null)
      assert(client.isInstanceOf[LlmClient[IO]])
    } finally {
      release.unsafeRunSync()
    }
  }
}
