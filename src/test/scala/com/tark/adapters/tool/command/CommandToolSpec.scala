package com.tark.adapters.tool.command

import com.tark.application.instances.all.given
import com.tark.domain.context.Context
import com.tark.domain.tool.{Tool, ToolContext}
import com.tark.ports.shared.tool.ToolExecutor
import munit.FunSuite

class CommandToolSpec extends FunSuite {
  test("CommandTool.stripQuotes: correctly strips wrapping quotes or backticks from commands") {
    assertEquals(CommandTool.stripQuotes("'ls -la'"), "ls -la")
    assertEquals(CommandTool.stripQuotes("\"pwd\""), "pwd")
    assertEquals(CommandTool.stripQuotes("`echo hello`"), "echo hello")
    assertEquals(CommandTool.stripQuotes("ls -la"), "ls -la")
  }

  test("CommandTool.generic: supports shell pipelines (e.g. ls | grep)") {
    val tool = CommandTool.generic
    val context = Context(Map.empty, Map.empty, List.empty)
    val toolContext = ToolContext(context, Map("command" -> "echo 'build.sbt' | grep build.sbt"), "exec_1")

    val result = summon[ToolExecutor[Tool]].execute(tool, toolContext)
    assertEquals(result.trim, "build.sbt")
  }
}
