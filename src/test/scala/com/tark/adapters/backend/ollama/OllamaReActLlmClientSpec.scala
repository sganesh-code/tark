package com.tark.adapters.backend.ollama

import com.tark.application.instances.all.given

import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.tool.{Tool, ToolCallRequest}
import sttp.client3.httpclient.cats.HttpClientCatsBackend
import com.tark.domain.Interaction
import com.tark.ports.outbound.react.ReActResponse
import io.circe.syntax.*

class OllamaReActLlmClientSpec extends FunSuite {

  test("OllamaReActLlmClient: extracts thought and tool call simultaneously on tool choice") {
    val responseJson = """{
      "choices": [
        {
          "message": {
            "role": "assistant",
            "content": "I should list the files to see what is in the repository.",
            "tool_calls": [
              {
                "id": "call_12345",
                "type": "function",
                "function": {
                  "name": "command_executor",
                  "arguments": "{\"command\":\"ls -la\"}"
                }
              }
            ]
          }
        }
      ]
    }"""

    val testingBackend = HttpClientCatsBackend.stub[IO]
      .whenRequestMatches(_.uri.path.contains("completions"))
      .thenRespond(responseJson)

    import com.tark.ports.outbound.react.ReActStrategy
    given strategy: ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] = new OllamaReActStrategy.NativeReActStrategy()
    val client = new OllamaReActLlmClient(testingBackend)(using strategy)
    val io = client.getCompletion("My prompt", List.empty, "system instructions", List.empty)
    val result = io.unsafeRunSync()

    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.thought, "I should list the files to see what is in the repository.")
    
    assert(response.action.isRight)
    val toolCall = response.action.toOption.get
    assertEquals(toolCall.toolName, "command_executor")
    assertEquals(toolCall.args.get("command"), Some("ls -la"))
  }

  test("OllamaReActLlmClient: extracts thought and treats text content as final answer when no tools called") {
    val responseJson = """{
      "choices": [
        {
          "message": {
            "role": "assistant",
            "content": "The final result is 42."
          }
        }
      ]
    }"""

    val testingBackend = HttpClientCatsBackend.stub[IO]
      .whenRequestMatches(_.uri.path.contains("completions"))
      .thenRespond(responseJson)

    val client = new OllamaReActLlmClient(testingBackend)
    val io = client.getCompletion("My prompt", List.empty, "system instructions", List.empty)
    val result = io.unsafeRunSync()

    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.thought, "The final result is 42.")
    
    assert(response.action.isLeft)
    assertEquals(response.action.left.toOption.get, "The final result is 42.")
  }

  test("OllamaReActLlmClient: parses forced structured JSON ReAct responses successfully") {
    val responseJson = """{
      "choices": [
        {
          "message": {
            "role": "assistant",
            "content": "{\"thought\": \"I want to run a command.\", \"tool\": \"command_executor\", \"arguments\": {\"command\": \"find . -name '*.py'\"}}"
          }
        }
      ]
    }"""

    val testingBackend = HttpClientCatsBackend.stub[IO]
      .whenRequestMatches(_.uri.path.contains("completions"))
      .thenRespond(responseJson)

    val client = new OllamaReActLlmClient(testingBackend)
    val io = client.getCompletion("My prompt", List.empty, "system instructions", List.empty)
    val result = io.unsafeRunSync()

    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.thought, "I want to run a command.")
    
    assert(response.action.isRight)
    val toolCall = response.action.toOption.get
    assertEquals(toolCall.toolName, "command_executor")
    assertEquals(toolCall.args.get("command"), Some("find . -name '*.py'"))
  }

  test("OllamaReActLlmClient: parses multiline raw content successfully") {
    val rawJson = """{
      |  "thought": "I need to explore the project structure to understand what kind of project this is. I'll start by checking what files and directories exist in the current location.",
      |  "tool": "command_executor",
      |  "arguments": {
      |    "command": "ls -la"
      |  }
      |}""".stripMargin

    val responseJson = s"""{
      |  "choices": [
      |    {
      |      "message": {
      |        "role": "assistant",
      |        "content": ${rawJson.asJson.noSpaces}
      |      }
      |    }
      |  ]
      |}""".stripMargin

    val testingBackend = HttpClientCatsBackend.stub[IO]
      .whenRequestMatches(_.uri.path.contains("completions"))
      .thenRespond(responseJson)

    val client = new OllamaReActLlmClient(testingBackend)
    val io = client.getCompletion("My prompt", List.empty, "system instructions", List.empty)
    val result = io.unsafeRunSync()

    assert(result.isRight)
    val response = result.toOption.get
    assertEquals(response.thought, "I need to explore the project structure to understand what kind of project this is. I'll start by checking what files and directories exist in the current location.")
    
    assert(response.action.isRight)
    val toolCall = response.action.toOption.get
    assertEquals(toolCall.toolName, "command_executor")
    assertEquals(toolCall.args.get("command"), Some("ls -la"))
  }
}
