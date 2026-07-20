package com.tark.ports.outbound.backend

import com.tark.ports.shared.serialization.Deserializable
import com.tark.ports.outbound.backend.TaskPlannerPrompt.given
import munit.FunSuite

class TaskPlannerPromptSpec extends FunSuite {

  test("TaskPlannerPrompt: parses valid JSON list of steps correctly using Deserializable typeclass") {
    val jsonInput =
      """
        |[
        |  "1. Design compilation architecture",
        |  "2. Build the token scanner",
        |  "3. Implement syntax parsing",
        |  "4. Run optimization pass"
        |]
      """.stripMargin

    val parser = summon[Deserializable[String, List[String]]]
    val result = parser.deserialize(jsonInput)

    assert(result.isRight)
    val steps = result.toOption.get
    assertEquals(steps, List(
      "1. Design compilation architecture",
      "2. Build the token scanner",
      "3. Implement syntax parsing",
      "4. Run optimization pass"
    ))
  }

  test("TaskPlannerPrompt: cleans and parses JSON wrapped in markdown code fences") {
    val fencedInput =
      """```json
        |[
        |  "1. Create sandbox container",
        |  "2. Run verification loop"
        |]
        |```""".stripMargin

    val parser = summon[Deserializable[String, List[String]]]
    val result = parser.deserialize(fencedInput)

    assert(result.isRight)
    val steps = result.toOption.get
    assertEquals(steps, List(
      "1. Create sandbox container",
      "2. Run verification loop"
    ))
  }

  test("TaskPlannerPrompt: falls back gracefully to line-by-line splitter on invalid JSON") {
    val rawText =
      """
        |- Step A: Research existing code
        |- Step B: Draft the port interface
        |* Step C: Complete tests and verify
      """.stripMargin

    val parser = summon[Deserializable[String, List[String]]]
    val result = parser.deserialize(rawText)

    assert(result.isRight)
    val steps = result.toOption.get
    assertEquals(steps, List(
      "Step A: Research existing code",
      "Step B: Draft the port interface",
      "Step C: Complete tests and verify"
    ))
  }
}
