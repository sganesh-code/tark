package com.tark.ports.shared.react

import com.tark.application.instances.all.given

import com.tark.domain.react.{CallTool, Finish, ReActState, ReActStep}
import com.tark.domain.tool.{Tool, ToolContext, ToolType}
import munit.FunSuite
import io.circe.Json

class ReActPromptAndParserSpec extends FunSuite {

  private val dummyTool1 = Tool("calculator", (ctx: ToolContext) => "2", ToolType.GenericTool)

  test("ReActPrompt: formats tools into prompt cleanly") {
    val tools = List(dummyTool1)
    val sysPrompt = ReActPrompt.systemPrompt(tools)

    assert(sysPrompt.contains("calculator"))
    assert(sysPrompt.contains("arguments"))
    assert(sysPrompt.contains("Thought -> Action -> Observation"))
  }

  test("ReActPrompt: formats history steps and user prompt correctly") {
    val state = ReActState("Solve 1 + 1")
      .copy(steps = List(
        ReActStep("First step is calculation", CallTool("calculator", Json.obj("args" -> Json.fromString("1+1"))), Some("2"))
      ))

    val usrPrompt = ReActPrompt.userPrompt(state)
    assert(usrPrompt.contains("Goal: Solve 1 + 1"))
    assert(usrPrompt.contains("First step is calculation"))
    assert(usrPrompt.contains("Action: calculator {\"args\":\"1+1\"}"))
    assert(usrPrompt.contains("Observation: 2"))
  }

  test("ReActParser: successfully parses JSON wrapped in markdown code blocks") {
    val sampleJsonAction =
      """```json
        |{
        |  "thought": "I need to run ls",
        |  "action": {
        |    "name": "command_executor",
        |    "arguments": { "command": "ls" }
        |  }
        |}
        |```""".stripMargin

    ReActParser.parseResponse(sampleJsonAction) match {
      case Left(err) => fail(s"Expected success but failed: $err")
      case Right((thought, CallTool(name, input))) =>
        assertEquals(thought, "I need to run ls")
        assertEquals(name, "command_executor")
        assertEquals(input, Json.obj("command" -> Json.fromString("ls")))
      case Right(other) => fail(s"Unexpected parse result: $other")
    }
  }

  test("ReActParser: successfully parses correct tool actions and finish answers") {
    val sampleAction =
      """Thought: I need to calculate 2 + 2.
        |I will call the calculator tool.
        |Action: calculator {"expr": "2+2"}""".stripMargin

    val sampleFinish =
      """Thought: We have completed everything successfully.
        |No more actions needed.
        |Finish: The final result is 4.""".stripMargin

    ReActParser.parseResponse(sampleAction) match {
      case Left(err) => fail(s"Expected success but failed: $err")
      case Right((thought, CallTool(name, input))) =>
        assertEquals(thought, "I need to calculate 2 + 2.\nI will call the calculator tool.")
        assertEquals(name, "calculator")
        assertEquals(input, Json.obj("expr" -> Json.fromString("2+2")))
      case Right(other) => fail(s"Unexpected parse result: $other")
    }

    ReActParser.parseResponse(sampleFinish) match {
      case Left(err) => fail(s"Expected success but failed: $err")
      case Right((thought, Finish(output))) =>
        assertEquals(thought, "We have completed everything successfully.\nNo more actions needed.")
        assertEquals(output, "The final result is 4.")
      case Right(other) => fail(s"Unexpected parse result: $other")
    }
  }

  test("ReActParser: handles malformed output and returns descriptive errors") {
    val missingThought = """Action: search {"q": "test"}"""
    val missingAction = """Thought: Testing something."""
    val badJson = """Thought: Let's run.
                    |Action: search {bad-json}""".stripMargin
    val wrongOrder = """Action: search {"q": "1"}
                       |Thought: Testing order.""".stripMargin

    assertEquals(ReActParser.parseResponse(missingThought), Left("Could not find 'Thought:' prefix in LLM response."))
    assertEquals(ReActParser.parseResponse(missingAction), Left("Could not find either 'Action:' or 'Finish:' prefix in LLM response."))
    assert(ReActParser.parseResponse(badJson).left.get.contains("Failed to parse Action JSON arguments"))
    assertEquals(ReActParser.parseResponse(wrongOrder), Left("'Action:' or 'Finish:' must appear after 'Thought:'."))
  }
}
