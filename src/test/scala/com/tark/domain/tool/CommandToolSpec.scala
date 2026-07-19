package com.tark.domain.tool

import munit.FunSuite

class CommandToolSpec extends FunSuite {
  test("commandFrom extracts command argument from ToolCall JSON") {
    val call = ToolCall("call_1", "function", ToolCallFunction("command_executor", """{"command":"echo hello"}"""))

    assertEquals(CommandTool.commandFrom(call), Right("echo hello"))
  }

  test("commandFrom reports missing command without leaking Circe decoding failures") {
    val call = ToolCall("call_1", "function", ToolCallFunction("command_executor", """{"cmd":"echo hello"}"""))

    assertEquals(CommandTool.commandFrom(call), Left("Tool argument 'command' is missing."))
  }

  test("commandFrom reports empty command") {
    val call = ToolCall("call_1", "function", ToolCallFunction("command_executor", """{"command":"   "}"""))

    assertEquals(CommandTool.commandFrom(call), Left("Tool argument 'command' is empty."))
  }
}
