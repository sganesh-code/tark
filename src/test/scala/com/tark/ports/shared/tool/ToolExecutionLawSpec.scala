package com.tark.ports.shared.tool

import com.tark.application.instances.all.given

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.context.Context
import com.tark.domain.tool.{Tool, ToolContext}
import io.circe.Json
import munit.FunSuite

class ToolExecutionLawSpec extends FunSuite {

  test("Tool laws: registry lookup after register returns an executable validated tool") {
    val registry = summon[ToolRegistry[Context]]
    val executor = summon[ToolExecutor[Tool]]
    val tool = Tool("custom_tool", (_: ToolContext) => "executed")
    val context = Context(Map.empty, Map.empty, List.empty)

    val registered = registry.register(context, tool)
    val lookedUp = registry.lookup(registered, "custom_tool")

    assertEquals(lookedUp.map(_.name), Some("custom_tool"))
    assertEquals(lookedUp.exists(executor.validate), true)
    assertEquals(lookedUp.map(executor.execute(_, ToolContext(registered, Map.empty, "exec-1"))), Some("executed"))
  }

  test("Tool laws: schema validation happens before execution receives ToolContext") {
    var executed = false
    val tool = Tool("command_executor", ctx => {
      executed = true
      ctx.args("command")
    })
    val definition = summon[ToToolDescription[Tool]].describe(tool)
    val validInput = Json.obj("command" -> Json.fromString("ls"))
    val invalidInput = Json.obj("command" -> Json.fromInt(1))
    val context = Context(Map("command_executor" -> tool), Map.empty, List.empty)
    val executor = summon[ToolExecutor[Tool]]

    assertEquals(ToolValidator.validate(definition, invalidInput), Left("Field 'command' must be a string"))
    assertEquals(executed, false)

    assertEquals(ToolValidator.validate(definition, validInput), Right(()))
    val result = executor.execute(tool, ToolContext(context, Map("command" -> "ls"), "exec-2"))
    assertEquals(result, "ls")
    assertEquals(executed, true)
  }

  test("ExecutableTool laws: effect-aware bridge uses caller-supplied suspension boundary") {
    var suspended = false
    val tool = Tool("custom_tool", (_: ToolContext) => "effect-result")
    val context = Context(Map("custom_tool" -> tool), Map.empty, List.empty)

    val executable = ExecutableTool.fromExecutor[IO, Tool] { thunk =>
      IO {
        suspended = true
        thunk()
      }
    }

    assertEquals(executable.validate(tool), true)
    assertEquals(suspended, false)

    val result = executable.execute(tool, ToolContext(context, Map.empty, "exec-3")).unsafeRunSync()
    assertEquals(result, "effect-result")
    assertEquals(suspended, true)
  }
}
