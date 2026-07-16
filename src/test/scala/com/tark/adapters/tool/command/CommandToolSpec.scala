package com.tark.adapters.tool.command

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.tool.{ToolCall, ToolCallFunction}
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

  test("execute runs command and returns ToolResult content") {
    val call = ToolCall("call_1", "function", ToolCallFunction("command_executor", """{"command":"printf tark"}"""))
    val context = Context(List(CommandTool.definition), Memory(), List.empty)

    val result = CommandTool.execute[IO](context, call).unsafeRunSync()

    assertEquals(result.content, "tark")
  }
}
