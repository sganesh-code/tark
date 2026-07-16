package com.tark.domain

import com.tark.domain.tool.OpenAIMessage

/**
 * Represents the structured state of an LLM agent execution, as described in
 * agent_harness_research_grounding.md. This canonical state allows the harness
 * to track goals, plans, constraints, and convergence conditions.
 */
case class AgentState(
                     goal: String = "",
                     deliverable: String = "",
                     constraints: List[String] = List.empty,
                     assumptions: List[String] = List.empty,
                     knownFacts: List[String] = List.empty,
                     openQuestions: List[String] = List.empty,
                     plan: List[String] = List.empty,
                     currentStep: Int = 0,
                     completedSteps: List[String] = List.empty,
                     toolResults: List[String] = List.empty,
                     candidateAnswer: Option[String] = None,
                     confidence: Double = 0.0,
                     done: Boolean = false,
                     reasonForStop: Option[String] = None,
                     messages: List[OpenAIMessage] = List.empty
                     ) {

  // Copy-based helper methods for easy and chainable state updates

  def withGoal(g: String): AgentState =
    copy(goal = g)

  def withDeliverable(d: String): AgentState =
    copy(deliverable = d)

  def addConstraint(c: String): AgentState =
    copy(constraints = constraints :+ c)

  def addAssumption(a: String): AgentState =
    copy(assumptions = assumptions :+ a)

  def addKnownFact(f: String): AgentState =
    copy(knownFacts = knownFacts :+ f)

  def addOpenQuestion(q: String): AgentState =
    copy(openQuestions = openQuestions :+ q)

  def withPlan(p: List[String]): AgentState =
    copy(plan = p)

  def withCurrentStep(step: Int): AgentState =
    copy(currentStep = step)

  def completeStep(step: String): AgentState =
    copy(completedSteps = completedSteps :+ step)

  def addToolResult(r: String): AgentState =
    copy(toolResults = toolResults :+ r)

  def withCandidateAnswer(ans: Option[String]): AgentState =
    copy(candidateAnswer = ans)

  def withConfidence(conf: Double): AgentState =
    copy(confidence = conf)

  def withDone(d: Boolean, reason: Option[String] = None): AgentState =
    copy(done = d, reasonForStop = reason)

  def withMessages(nextMessages: List[OpenAIMessage]): AgentState =
    copy(messages = nextMessages)

  def addMessage(message: OpenAIMessage): AgentState =
    copy(messages = messages :+ message)
}

object AgentState {
  import io.circe.{Encoder, Decoder}
  import io.circe.generic.semiauto.*

  given Encoder[AgentState] = deriveEncoder
  given Decoder[AgentState] = deriveDecoder
}
