package com.tark.ports.shared.react

import com.tark.application.instances.all.given

import com.tark.domain.react.{CallTool, Finish, ReActState, ReActStep}
import munit.FunSuite
import io.circe.Json

class ReActTraceSerializerSpec extends FunSuite {

  test("ReActTraceSerializer: serializes a ReActState trace cleanly into readable markdown") {
    val state = ReActState("Solve 2 + 2", maxSteps = 5)
      .copy(
        steps = List(
          ReActStep("I should use calculator", CallTool("calculator", Json.obj("args" -> Json.fromString("2+2"))), Some("4")),
          ReActStep("The result is 4. Done.", Finish("The answer is 4"))
        ),
        done = true,
        reasonForStop = Some("verifier_passed")
      )

    val md = ReActTraceSerializer.serialize(state)

    assert(md.contains("# ReAct Execution Trace"))
    assert(md.contains("**Goal:** Solve 2 + 2"))
    assert(md.contains("**Status:** Completed"))
    assert(md.contains("**Termination Reason:** verifier_passed"))
    assert(md.contains("**Total Steps:** 2 / 5"))
    assert(md.contains("### Step 1"))
    assert(md.contains("**Thought:** I should use calculator"))
    assert(md.contains("calculator"))
    assert(md.contains("args"))
    assert(md.contains("**Observation:**\n```\n4\n```"))
    assert(md.contains("### Step 2"))
    assert(md.contains("**Thought:** The result is 4. Done."))
    assert(md.contains("The answer is 4"))
  }
}
