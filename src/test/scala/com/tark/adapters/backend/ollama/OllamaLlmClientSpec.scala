package com.tark.adapters.backend.ollama

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.Interaction
import munit.FunSuite
import sttp.client3.httpclient.cats.HttpClientCatsBackend

class OllamaLlmClientSpec extends FunSuite {
  test("OllamaLlmClient: formats history roles and maps responses correctly") {
    val responseJson = """{
      "choices": [
        {
          "message": {
            "role": "assistant",
            "content": "Hello! I am Ollama."
          }
        }
      ]
    }"""

    var capturedRequest: Option[OllamaRequest] = None

    val testingBackend: sttp.client3.SttpBackend[IO, Any] = HttpClientCatsBackend.stub[IO]
      .whenRequestMatches(_.uri.path.contains("completions"))
      .thenRespondF { request =>
        IO {
          import io.circe.parser.decode
          import sttp.client3.testing.RichTestingRequest

          capturedRequest = decode[OllamaRequest](request.forceBodyAsString).toOption
          sttp.client3.Response.ok(responseJson)
        }
      }

    val client = new OllamaLlmClient(testingBackend)
    val history = List(Interaction("1", "Hi", "Hello", 12345L, "llm_completion"))

    val completion = client.getCompletion("My new prompt", history, "system note")
    val result = completion.unsafeRunSync()

    assertEquals(result, Left("Hello! I am Ollama."))
    assertEquals(capturedRequest.map(_.model), Some("qwen3-coder:30b"))
    assertEquals(capturedRequest.flatMap(_.format), Some("json"))
    assertEquals(capturedRequest.flatMap(_.tools), None)
    assertEquals(
      capturedRequest.map(_.messages.map(message => message.role -> message.content)),
      Some(List(
        "system" -> Some("system note"),
        "user" -> Some("Hi"),
        "assistant" -> Some("Hello"),
        "user" -> Some("My new prompt")
      ))
    )
  }
}
