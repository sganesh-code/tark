package com.tark.domain

import com.tark.application.instances.all.given

import com.tark.domain.context.Context
import munit.FunSuite
import com.tark.ports.shared.serialization.Serializable
import com.tark.ports.outbound.context.ContextOps

class AgentStateSpec extends FunSuite {

  test("AgentState: initializes with correct defaults") {
    val state = AgentState()
    assertEquals(state.goal, "")
    assertEquals(state.deliverable, "")
    assertEquals(state.constraints, List.empty[String])
    assertEquals(state.assumptions, List.empty[String])
    assertEquals(state.knownFacts, List.empty[String])
    assertEquals(state.openQuestions, List.empty[String])
    assertEquals(state.plan, List.empty[String])
    assertEquals(state.currentStep, 0)
    assertEquals(state.completedSteps, List.empty[String])
    assertEquals(state.toolResults, List.empty[String])
    assertEquals(state.candidateAnswer, None)
    assertEquals(state.confidence, 0.0)
    assertEquals(state.done, false)
    assertEquals(state.reasonForStop, None)
  }

  test("AgentState: copy-based helper methods work correctly") {
    val state = AgentState()
      .withGoal("My Goal")
      .withDeliverable("My Deliverable")
      .addConstraint("Constraint 1")
      .addConstraint("Constraint 2")
      .addAssumption("Assumption 1")
      .addKnownFact("Fact 1")
      .addOpenQuestion("Question 1")
      .withPlan(List("Step 1", "Step 2"))
      .withCurrentStep(1)
      .completeStep("Step 1")
      .addToolResult("Result 1")
      .withCandidateAnswer(Some("Answer"))
      .withConfidence(0.9)
      .withDone(true, Some("completed"))

    assertEquals(state.goal, "My Goal")
    assertEquals(state.deliverable, "My Deliverable")
    assertEquals(state.constraints, List("Constraint 1", "Constraint 2"))
    assertEquals(state.assumptions, List("Assumption 1"))
    assertEquals(state.knownFacts, List("Fact 1"))
    assertEquals(state.openQuestions, List("Question 1"))
    assertEquals(state.plan, List("Step 1", "Step 2"))
    assertEquals(state.currentStep, 1)
    assertEquals(state.completedSteps, List("Step 1"))
    assertEquals(state.toolResults, List("Result 1"))
    assertEquals(state.candidateAnswer, Some("Answer"))
    assertEquals(state.confidence, 0.9)
    assertEquals(state.done, true)
    assertEquals(state.reasonForStop, Some("completed"))
  }

  test("Context: direct helper methods manipulate nested AgentState cleanly") {
    val initialContext = Context(Map.empty, Map.empty, List.empty)
    assert(initialContext.agentState.isEmpty)

    val updated = initialContext
      .withGoal("Build an Agent")
      .withDeliverable("Working Harness")
      .addConstraint("Must use Scala 3")
      .addAssumption("Docker is installed")
      .addKnownFact("The sandbox is safe")
      .addOpenQuestion("How to optimize context?")
      .withPlan(List("Define state", "Implement loop"))
      .withCurrentStep(0)
      .completeStep("Define state")
      .addToolResult("Successful run")
      .withCandidateAnswer(Some("Success"))
      .withConfidence(1.0)
      .withDone(true, Some("reached_end"))

    val state = updated.agentState.get
    assertEquals(state.goal, "Build an Agent")
    assertEquals(state.deliverable, "Working Harness")
    assertEquals(state.constraints, List("Must use Scala 3"))
    assertEquals(state.assumptions, List("Docker is installed"))
    assertEquals(state.knownFacts, List("The sandbox is safe"))
    assertEquals(state.openQuestions, List("How to optimize context?"))
    assertEquals(state.plan, List("Define state", "Implement loop"))
    assertEquals(state.currentStep, 0)
    assertEquals(state.completedSteps, List("Define state"))
    assertEquals(state.toolResults, List("Successful run"))
    assertEquals(state.candidateAnswer, Some("Success"))
    assertEquals(state.confidence, 1.0)
    assertEquals(state.done, true)
    assertEquals(state.reasonForStop, Some("reached_end"))
  }

  test("ContextOps: typeclass provides get and update capabilities on Context") {
    val initialContext = Context(Map.empty, Map.empty, List.empty)
    val ops = summon[ContextOps[Context]]

    assertEquals(ops.getAgentState(initialContext), None)

    val updated = ops.updateAgentState(initialContext, _.withGoal("Learn Cats Effect"))
    val state = ops.getAgentState(updated).get
    assertEquals(state.goal, "Learn Cats Effect")
  }

  test("ContextOps laws: updateAgentState composes and leaves source context unchanged") {
    val initialContext = Context(Map.empty, Map.empty, List.empty)
    val ops = summon[ContextOps[Context]]

    val f: AgentState => AgentState = _.withGoal("Goal")
    val g: AgentState => AgentState = _.addConstraint("Constraint")

    val sequential = ops.updateAgentState(ops.updateAgentState(initialContext, f), g)
    val composed = ops.updateAgentState(initialContext, f.andThen(g))

    assertEquals(ops.getAgentState(sequential), ops.getAgentState(composed))
    assertEquals(ops.getAgentState(initialContext), None)
  }

  test("AgentState laws: append helpers preserve order and prior values") {
    val initial = AgentState().withGoal("Goal")
    val updated = initial
      .addConstraint("first")
      .addConstraint("second")
      .completeStep("step-1")
      .completeStep("step-2")
      .addToolResult("result-1")
      .addToolResult("result-2")

    assertEquals(updated.goal, "Goal")
    assertEquals(updated.constraints, List("first", "second"))
    assertEquals(updated.completedSteps, List("step-1", "step-2"))
    assertEquals(updated.toolResults, List("result-1", "result-2"))
    assertEquals(initial.constraints, List.empty[String])
    assertEquals(initial.completedSteps, List.empty[String])
    assertEquals(initial.toolResults, List.empty[String])
  }

  test("Serializable: outputs beautifully formatted Agent State when present") {
    val context = Context(Map.empty, Map.empty, List.empty)
      .withGoal("Validate harness")
      .withDeliverable("Test spec")
      .addConstraint("Strict boundaries")
      .addAssumption("Scala compiler is ready")
      .addKnownFact("Everything passes")
      .addOpenQuestion("Is it fast?")
      .withPlan(List("Build", "Test"))
      .withCurrentStep(0)
      .completeStep("Build")
      .addToolResult("Clean log")
      .withCandidateAnswer(Some("Pass"))
      .withConfidence(0.95)
      .withDone(false)

    val serialized = summon[Serializable[Context, String]].serialize(context)

    assert(serialized.contains("## Agent State"))
    assert(serialized.contains("- **Goal**: Validate harness"))
    assert(serialized.contains("- **Deliverable**: Test spec"))
    assert(serialized.contains("- **Constraints**:"))
    assert(serialized.contains("  - Strict boundaries"))
    assert(serialized.contains("- **Assumptions**:"))
    assert(serialized.contains("  - Scala compiler is ready"))
    assert(serialized.contains("- **Known Facts**:"))
    assert(serialized.contains("  - Everything passes"))
    assert(serialized.contains("- **Open Questions**:"))
    assert(serialized.contains("  - Is it fast?"))
    assert(serialized.contains("- **Plan**:"))
    assert(serialized.contains("  -> 1. Build"))
    assert(serialized.contains("    2. Test"))
    assert(serialized.contains("- **Completed Steps**:"))
    assert(serialized.contains("  - Build"))
    assert(serialized.contains("- **Tool Results**:"))
    assert(serialized.contains("  - Clean log"))
    assert(serialized.contains("- **Candidate Answer**: Pass"))
    assert(serialized.contains("- **Confidence**: 0.95"))
    assert(serialized.contains("- **Done**: false"))
    assert(serialized.contains("- **Reason for Stop**: None"))
  }
}
