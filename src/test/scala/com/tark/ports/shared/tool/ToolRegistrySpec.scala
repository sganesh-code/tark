package com.tark.ports.shared.tool

import com.tark.application.instances.all.given
import com.tark.domain.context.Context
import com.tark.domain.tool.Tool
import munit.FunSuite

class ToolRegistrySpec extends FunSuite {
  test("ToolRegistry: allows registering and looking up tools in Context") {
    val context = Context(Map.empty, Map.empty, List.empty)
    val tool = Tool("test_tool", _ => "ok")

    val registry = summon[ToolRegistry[Context]]
    val updatedContext = registry.register(context, tool)

    assertEquals(registry.lookup(updatedContext, "test_tool"), Some(tool))
    assertEquals(registry.lookup(updatedContext, "non_existent"), None)
  }
}
