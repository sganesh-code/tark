package com.tark.ports.outbound.backend

import com.tark.domain.GoalContract
import com.tark.ports.shared.serialization.Deserializable
import com.tark.ports.outbound.backend.GoalContractPrompt.given
import munit.FunSuite

class GoalContractPromptSpec extends FunSuite {

  test("GoalContractPrompt: parses valid JSON string correctly using Deserializable typeclass") {
    val jsonInput =
      """
        |{
        |  "goal": "Build a CLI agent",
        |  "deliverable": "Functional Scala app",
        |  "constraints": ["Scala 3", "Cats Effect"],
        |  "assumptions": ["Ollama runs locally"],
        |  "knownFacts": ["Project runs on port 8080"]
        |}
      """.stripMargin

    val parser = summon[Deserializable[String, GoalContract]]
    val result = parser.deserialize(jsonInput)

    assert(result.isRight)
    val contract = result.toOption.get
    assertEquals(contract.goal, "Build a CLI agent")
    assertEquals(contract.deliverable, "Functional Scala app")
    assertEquals(contract.constraints, List("Scala 3", "Cats Effect"))
    assertEquals(contract.assumptions, List("Ollama runs locally"))
    assertEquals(contract.knownFacts, List("Project runs on port 8080"))
  }

  test("GoalContractPrompt: cleans and parses JSON wrapped in markdown code fences") {
    val fencedInput =
      """```json
        |{
        |  "goal": "Write tests",
        |  "deliverable": "Tests pass",
        |  "constraints": ["munit"],
        |  "assumptions": [],
        |  "knownFacts": []
        |}
        |```""".stripMargin

    val parser = summon[Deserializable[String, GoalContract]]
    val result = parser.deserialize(fencedInput)

    assert(result.isRight)
    val contract = result.toOption.get
    assertEquals(contract.goal, "Write tests")
    assertEquals(contract.deliverable, "Tests pass")
    assertEquals(contract.constraints, List("munit"))
    assertEquals(contract.assumptions, List.empty)
    assertEquals(contract.knownFacts, List.empty)
  }

  test("GoalContractPrompt: falls back gracefully to line-by-line parser on invalid JSON") {
    val rawText =
      """
        |GOAL: Implement a game using Scala
        |DELIVERABLE: A playable snake game
        |CONSTRAINTS:
        |- Use Vanilla JS or pure console
        |- Thread safety is required
        |ASSUMPTIONS:
        |* Framerate is 60fps
        |KNOWN_FACTS:
        |- Current OS is macOS
      """.stripMargin

    val parser = summon[Deserializable[String, GoalContract]]
    val result = parser.deserialize(rawText)

    assert(result.isRight)
    val contract = result.toOption.get
    assertEquals(contract.goal, "Implement a game using Scala")
    assertEquals(contract.deliverable, "A playable snake game")
    assertEquals(contract.constraints, List("Use Vanilla JS or pure console", "Thread safety is required"))
    assertEquals(contract.assumptions, List("Framerate is 60fps"))
    assertEquals(contract.knownFacts, List("Current OS is macOS"))
  }

  test("GoalContractPrompt: handles empty or unstructured input with defaults") {
    val rawText = "Just some random thoughts without any section markers."
    val parser = summon[Deserializable[String, GoalContract]]
    val result = parser.deserialize(rawText)

    assert(result.isRight)
    val contract = result.toOption.get
    assertEquals(contract.goal, "Just some random thoughts without any section markers.")
    assertEquals(contract.deliverable, "Deliver completed task")
    assertEquals(contract.constraints, List.empty)
    assertEquals(contract.assumptions, List.empty)
    assertEquals(contract.knownFacts, List.empty)
  }
}
