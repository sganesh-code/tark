package com.tark.ports.shared.tool

import com.tark.application.instances.all.given

import com.tark.domain.tool.{FunctionDefinition, FunctionParameters, FunctionProperty, Tool, ToolContext, ToolDefinition}
import munit.FunSuite
import io.circe.Json

class ToolValidatorSpec extends FunSuite {

  private val testToolDefinition = ToolDefinition(
    `type` = "function",
    function = FunctionDefinition(
      name = "search",
      description = "Search for details",
      parameters = FunctionParameters(
        properties = Map(
          "query" -> FunctionProperty("string", "The search query"),
          "limit" -> FunctionProperty("integer", "Max results to return"),
          "flag" -> FunctionProperty("boolean", "Active flag")
        ),
        required = List("query", "flag")
      )
    )
  )

  test("ToolValidator: validates valid and invalid input objects against standard ToolDefinition") {
    val validJson = Json.obj(
      "query" -> Json.fromString("test"),
      "limit" -> Json.fromInt(5),
      "flag" -> Json.fromBoolean(true)
    )

    val validOptionalOmitted = Json.obj(
      "query" -> Json.fromString("test"),
      "flag" -> Json.fromBoolean(false)
    )

    val missingRequired = Json.obj(
      "query" -> Json.fromString("test")
    )

    val invalidType = Json.obj(
      "query" -> Json.fromString("test"),
      "limit" -> Json.fromString("five"), // Should be number/integer
      "flag" -> Json.fromBoolean(true)
    )

    val notAnObject = Json.fromString("not-an-object")

    assertEquals(ToolValidator.validate(testToolDefinition, validJson), Right(()))
    assertEquals(ToolValidator.validate(testToolDefinition, validOptionalOmitted), Right(()))
    assertEquals(ToolValidator.validate(testToolDefinition, missingRequired), Left("Missing required field: flag"))
    assertEquals(ToolValidator.validate(testToolDefinition, invalidType), Left("Field 'limit' must be a number"))
    assertEquals(ToolValidator.validate(testToolDefinition, notAnObject), Left("Input must be a JSON object"))
  }

  test("Tool laws: ToToolDescription describes the same logical tool name") {
    val tool = Tool("custom_tool", (_: ToolContext) => "ok")
    val description = summon[ToToolDescription[Tool]].describe(tool)

    assertEquals(description.function.name, tool.name)
    assertEquals(description.function.parameters.required, description.function.parameters.properties.keys.toList)
  }
}
