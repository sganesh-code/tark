package com.tark.ports.outbound.backend

import com.tark.ports.shared.serialization.Deserializable
import com.tark.ports.outbound.backend.ProgressTrackerPrompt.given
import munit.FunSuite

class ProgressTrackerPromptSpec extends FunSuite {

  test("ProgressTrackerPrompt: parses completed JSON correctly as true") {
    val jsonInput = """{"completed": true, "reason": "All tasks completed"}"""
    val parser = summon[Deserializable[String, Boolean]]
    assertEquals(parser.deserialize(jsonInput), Right(true))
  }

  test("ProgressTrackerPrompt: parses incomplete JSON correctly as false") {
    val jsonInput = """{"completed": false, "reason": "Further tests are needed"}"""
    val parser = summon[Deserializable[String, Boolean]]
    assertEquals(parser.deserialize(jsonInput), Right(false))
  }

  test("ProgressTrackerPrompt: handles raw text fallback cleanly") {
    assertEquals(summon[Deserializable[String, Boolean]].deserialize("The step is completed, this is true."), Right(true))
    assertEquals(summon[Deserializable[String, Boolean]].deserialize("This is still ongoing."), Right(false))
  }
}
