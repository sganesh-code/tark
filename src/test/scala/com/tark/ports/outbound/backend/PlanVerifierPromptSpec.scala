package com.tark.ports.outbound.backend

import com.tark.ports.shared.serialization.Deserializable
import com.tark.ports.outbound.backend.PlanVerifierPrompt.given
import munit.FunSuite

class PlanVerifierPromptSpec extends FunSuite {

  test("PlanVerifierPrompt: parses valid confirmation JSON correctly as true") {
    val jsonInput = """{"valid": true, "reason": "Looks excellent"}"""
    val parser = summon[Deserializable[String, Boolean]]
    assertEquals(parser.deserialize(jsonInput), Right(true))
  }

  test("PlanVerifierPrompt: parses invalid rejection JSON correctly as false") {
    val jsonInput = """{"valid": false, "reason": "Missing constraint validation"}"""
    val parser = summon[Deserializable[String, Boolean]]
    assertEquals(parser.deserialize(jsonInput), Right(false))
  }

  test("PlanVerifierPrompt: handles raw text fallback cleanly") {
    assertEquals(summon[Deserializable[String, Boolean]].deserialize("The plan is perfectly valid and true."), Right(true))
    assertEquals(summon[Deserializable[String, Boolean]].deserialize("This is totally incorrect."), Right(false))
  }
}
