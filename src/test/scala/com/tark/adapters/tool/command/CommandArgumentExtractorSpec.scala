package com.tark.adapters.tool.command

import com.tark.domain.tool.{ToolCall, ToolCallFunction}
import munit.FunSuite

class CommandArgumentExtractorSpec extends FunSuite {
  test("commandFrom extracts command argument from ToolCall JSON") {
    val call = ToolCall("call_1", "function", ToolCallFunction("command_executor", """{"command":"echo hello"}"""))

    assertEquals(CommandArgumentExtractor.commandFrom(call), Right("echo hello"))
  }

  test("commandFrom reports missing command without leaking Circe decoding failures") {
    val call = ToolCall("call_1", "function", ToolCallFunction("command_executor", """{"cmd":"echo hello"}"""))

    assertEquals(CommandArgumentExtractor.commandFrom(call), Left("Tool argument 'command' is missing."))
  }

  test("commandFrom reports empty command") {
    val call = ToolCall("call_1", "function", ToolCallFunction("command_executor", """{"command":"   "}"""))

    assertEquals(CommandArgumentExtractor.commandFrom(call), Left("Tool argument 'command' is empty."))
  }
}
