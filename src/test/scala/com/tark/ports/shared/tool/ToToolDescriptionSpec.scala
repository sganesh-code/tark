package com.tark.ports.shared.tool

import com.tark.domain.tool.Tool
import munit.FunSuite

class ToToolDescriptionSpec extends FunSuite {
  test("ToToolDescription: successfully describes registered tool") {
    import ToToolDescription.given

    val tool = Tool("command_executor", _ => "file1.txt")
    val desc = summon[ToToolDescription[Tool]].describe(tool)

    assertEquals(desc.function.name, "command_executor")
    assert(desc.function.description.contains("Docker sandbox"))
    assert(desc.function.parameters.properties.contains("command"))
  }
}
