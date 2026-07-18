package com.tark.adapters.backend.ollama

import com.tark.ports.outbound.backend.LlmStreamEvent
import munit.FunSuite

class OllamaLlmClientSpec extends FunSuite {
  test("eventsFromPayload decodes content deltas") {
    val payload =
      """{"choices":[{"index":0,"delta":{"content":"hello"},"finish_reason":null}]}"""

    assertEquals(OllamaLlmClient.eventsFromPayload(payload), Right(List(LlmStreamEvent.ContentDelta("hello"))))
  }

  test("eventsFromPayload decodes tool call argument deltas without finalizing") {
    val payload =
      """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"command_executor","arguments":"{\"command\":"}}]},"finish_reason":null}]}"""

    assertEquals(
      OllamaLlmClient.eventsFromPayload(payload),
      Right(
        List(
          LlmStreamEvent.ToolCallDelta(
            index = 0,
            id = Some("call_1"),
            callType = Some("function"),
            name = Some("command_executor"),
            argumentsChunk = Some("""{"command":""")
          )
        )
      )
    )
  }
}
