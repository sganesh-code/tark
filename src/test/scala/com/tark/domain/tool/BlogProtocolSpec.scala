package com.tark.domain.tool

import io.circe.parser.decode
import io.circe.syntax.*
import munit.FunSuite

class BlogProtocolSpec extends FunSuite {
  test("OpenAIMessage encoder omits absent optional fields") {
    val json = OpenAIMessage(role = "user", content = Some("hello")).asJson

    assertEquals(json.hcursor.get[String]("role"), Right("user"))
    assertEquals(json.hcursor.get[String]("content"), Right("hello"))
    assert(json.hcursor.downField("tool_calls").focus.isEmpty)
    assert(json.hcursor.downField("tool_call_id").focus.isEmpty)
  }

  test("ToolCall codecs preserve OpenAI-compatible function payload") {
    val call = ToolCall(
      id = "call_1",
      `type` = "function",
      function = ToolCallFunction("command_executor", """{"command":"pwd"}""")
    )

    val decoded = decode[ToolCall](call.asJson.noSpaces)

    assertEquals(decoded, Right(call))
  }

  test("ToolDefinition supports Blog command_executor schema") {
    val definition = ToolDefinition(
      `type` = "function",
      function = OpenAIFunction(
        name = "command_executor",
        description = "Execute commands",
        parameters = OpenAIFunctionParams.Str(description = "command JSON")
      )
    )

    assertEquals(definition.asJson.hcursor.downField("function").get[String]("name"), Right("command_executor"))
  }
}
