package com.tark.domain

import com.tark.application.instances.all.given

import com.tark.domain.react.{CallTool, Finish, ReActState, ReActStep}
import com.tark.ports.shared.react.ReActStateOps
import munit.FunSuite
import io.circe.Json

class ReActStateSpec extends FunSuite {

  test("ReActState: initializes with correct defaults") {
    val state = ReActState("Solve the task")
    assertEquals(state.goal, "Solve the task")
    assertEquals(state.steps, List.empty[ReActStep])
    assertEquals(state.maxSteps, 10)
    assertEquals(state.done, false)
    assertEquals(state.reasonForStop, None)
  }

  test("ReActStateOps: addStep works correctly and preserves immutability") {
    val state = ReActState("Solve the task")
    val ops = summon[ReActStateOps[ReActState]]

    val step = ReActStep("I should call search", CallTool("search", Json.fromString("query")))
    val updated = ops.addStep(state, step)

    // Original state is unchanged (immutability)
    assertEquals(state.steps, List.empty[ReActStep])

    // Updated state has the new step
    assertEquals(updated.steps.size, 1)
    assertEquals(updated.steps.head.thought, "I should call search")
    assertEquals(updated.steps.head.action, CallTool("search", Json.fromString("query")))
  }

  test("ReActStateOps: updateLastObservation works correctly and preserves immutability") {
    val state = ReActState("Solve the task")
    val ops = summon[ReActStateOps[ReActState]]

    // If no steps exist, updateLastObservation should return the original state
    val noStepsUpdated = ops.updateLastObservation(state, "some observation")
    assertEquals(noStepsUpdated.steps, List.empty[ReActStep])

    val step1 = ReActStep("Thinking...", CallTool("calculator", Json.fromString("1+1")))
    val withStep = ops.addStep(state, step1)
    val withObs = ops.updateLastObservation(withStep, "2")

    // Original step in withStep remains with observation = None
    assertEquals(withStep.steps.head.observation, None)

    // Updated step has the observation
    assertEquals(withObs.steps.head.observation, Some("2"))
  }

  test("ReActStateOps: markDone works correctly and preserves immutability") {
    val state = ReActState("Solve the task")
    val ops = summon[ReActStateOps[ReActState]]

    val updated = ops.markDone(state, "verifier_passed")

    assertEquals(state.done, false)
    assertEquals(updated.done, true)
    assertEquals(updated.reasonForStop, Some("verifier_passed"))
  }

  test("ReActStateOps: isBudgetExceeded identifies when maximum step limit is reached") {
    val state = ReActState("Solve the task", maxSteps = 2)
    val ops = summon[ReActStateOps[ReActState]]

    val step1 = ReActStep("T1", Finish("ans1"))
    val step2 = ReActStep("T2", Finish("ans2"))

    val s1 = ops.addStep(state, step1)
    assertEquals(ops.isBudgetExceeded(s1), false)

    val s2 = ops.addStep(s1, step2)
    assertEquals(ops.isBudgetExceeded(s2), true)
  }

  test("ReActStateOps laws: markDone preserves steps and budget exceeded is monotonic") {
    val ops = summon[ReActStateOps[ReActState]]
    val state = ReActState("Solve the task", maxSteps = 1)
    val step1 = ReActStep("T1", Finish("ans1"))
    val step2 = ReActStep("T2", Finish("ans2"))

    val atBudget = ops.addStep(state, step1)
    val overBudget = ops.addStep(atBudget, step2)
    val done = ops.markDone(overBudget, "complete")

    assertEquals(done.done, true)
    assertEquals(done.reasonForStop, Some("complete"))
    assertEquals(done.steps, overBudget.steps)
    assertEquals(ops.isBudgetExceeded(atBudget), true)
    assertEquals(ops.isBudgetExceeded(overBudget), true)
    assertEquals(state.steps, List.empty[ReActStep])
  }

  test("ReActStateOps laws: updateLastObservation updates only the latest step") {
    val ops = summon[ReActStateOps[ReActState]]
    val state = ReActState("Solve the task")
    val step1 = ReActStep("T1", CallTool("search", Json.obj("q" -> Json.fromString("one"))), Some("old"))
    val step2 = ReActStep("T2", CallTool("search", Json.obj("q" -> Json.fromString("two"))), None)

    val withSteps = ops.addStep(ops.addStep(state, step1), step2)
    val updated = ops.updateLastObservation(withSteps, "latest")

    assertEquals(updated.steps.head, step1)
    assertEquals(updated.steps.last, step2.copy(observation = Some("latest")))
    assertEquals(withSteps.steps.last.observation, None)
  }
}
