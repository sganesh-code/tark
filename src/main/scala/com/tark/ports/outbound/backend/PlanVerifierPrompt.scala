package com.tark.ports.outbound.backend

import com.tark.domain.GoalContract
import com.tark.ports.shared.serialization.Deserializable

object PlanVerifierPrompt {

  def systemInstructions: String = {
    """You are a strict Plan Verifier. Your job is to analyze the proposed checklist plan against the original Goal Contract.
      |
      |Validate that:
      |1. The plan logically addresses the primary goal.
      |2. The plan directly leads to the testable deliverable.
      |3. The plan respects and enforces all defined constraints.
      |4. The plan steps have a logical sequential flow with no redundant or circular steps.
      |
      |You MUST return your response as a single, valid JSON object with the following schema:
      |{
      |  "valid": true,
      |  "reason": "explanation of why it is valid or why it is invalid"
      |}
      |
      |If the plan is logically complete and sound, set "valid" to true.
      |If the plan misses critical constraints or has flaws, set "valid" to false.
      |
      |Your response MUST be valid JSON only. Do not output any XML tags, markdown blocks (like ```json), preambles, introductory or concluding text.
      |""".stripMargin
  }

  def userPrompt(contract: GoalContract, plan: List[String]): String = {
    val constraintsStr = if (contract.constraints.nonEmpty) contract.constraints.map(c => s"- $c").mkString("\n") else "(None)"
    val planStr = plan.map(step => s"  * $step").mkString("\n")
    s"""Goal Contract:
       |* Goal: ${contract.goal}
       |* Deliverable: ${contract.deliverable}
       |* Constraints:
       |$constraintsStr
       |
       |Proposed Plan Steps:
       |$planStr
       |
       |Please execute the plan validation check now.
       |""".stripMargin
  }

  // Provide a given instance of Deserializable to parse the validation result into a Boolean cleanly
  given Deserializable[String, Boolean] with {
    override def deserialize(data: String): Either[Throwable, Boolean] = {
      val trimmed = data.trim.toLowerCase
      if (trimmed.contains("true") || trimmed.contains("\"valid\": true") || trimmed.contains("\"valid\":true")) {
        Right(true)
      } else {
        Right(false)
      }
    }
  }
}
