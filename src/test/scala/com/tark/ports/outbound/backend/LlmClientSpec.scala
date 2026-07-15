package com.tark.ports.outbound.backend

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.Interaction
import com.tark.domain.tool.{Tool, ToolCallRequest}
import munit.FunSuite

class LlmClientSpec extends FunSuite {
  test("LlmClient: gets completion response from stub client") {
    val client = summon[LlmClient[IO]]
    val io = client.getCompletion("Explain recursion", List.empty, "System prompt")

    val response = io.unsafeRunSync()
    assertEquals(response, Left("Stub Response to: 'Explain recursion'"))
  }

  test("LlmClient: supports custom local stubs for testing") {
    val customClient = new LlmClient[IO] {
      override def getCompletion(prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]): IO[Either[String, List[ToolCallRequest]]] =
        IO.pure(Left("Custom mock!"))
    }

    val response = customClient.getCompletion("hello", List.empty, "System prompt").unsafeRunSync()
    assertEquals(response, Left("Custom mock!"))
  }
}
