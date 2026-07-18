package com.tark.ports.outbound.backend

import com.tark.domain.tool.ToolCallFunction
import munit.FunSuite

class ToolCallAccumulatorSpec extends FunSuite {
  test("ToolCallAccumulator assembles function arguments split across deltas") {
    val completed = ToolCallAccumulator.empty
      .add(LlmStreamEvent.ToolCallDelta(index = 0, id = Some("call_1"), callType = Some("function")))
      .add(LlmStreamEvent.ToolCallDelta(index = 0, name = Some("command_executor"), argumentsChunk = Some("""{"command":""")))
      .add(LlmStreamEvent.ToolCallDelta(index = 0, argumentsChunk = Some(""""echo hi"}""")))
      .complete

    assertEquals(completed.map(_.map(_.function)), Right(List(ToolCallFunction("command_executor", """{"command":"echo hi"}"""))))
  }

  test("ToolCallAccumulator reports invalid partial JSON after finalization") {
    val completed = ToolCallAccumulator.empty
      .add(LlmStreamEvent.ToolCallDelta(index = 0, id = Some("call_1"), callType = Some("function")))
      .add(LlmStreamEvent.ToolCallDelta(index = 0, name = Some("command_executor"), argumentsChunk = Some("""{"command":""")))
      .complete

    assert(completed.left.exists(_.exists(_.contains("invalid JSON arguments"))))
  }

  test("ToolCallAccumulator preserves multiple tool call order by index") {
    val completed = ToolCallAccumulator.empty
      .add(LlmStreamEvent.ToolCallDelta(index = 1, id = Some("call_2"), callType = Some("function"), name = Some("command_executor"), argumentsChunk = Some("""{"command":"pwd"}""")))
      .add(LlmStreamEvent.ToolCallDelta(index = 0, id = Some("call_1"), callType = Some("function"), name = Some("command_executor"), argumentsChunk = Some("""{"command":"ls"}""")))
      .complete

    assertEquals(completed.map(_.map(_.id)), Right(List("call_1", "call_2")))
  }
}
