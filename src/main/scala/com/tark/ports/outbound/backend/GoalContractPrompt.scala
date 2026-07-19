package com.tark.ports.outbound.backend

import com.tark.domain.GoalContract
import com.tark.ports.shared.serialization.Deserializable
import io.circe.parser.*

object GoalContractPrompt {

  def systemInstructions: String = {
    """You are a meticulous task-intake agent. Your job is to analyze the user's initial prompt and establish a clear, structured Goal Contract.
      |
      |Analyze the input and identify:
      |1. The overarching GOAL of the session.
      |2. The concrete, testable DELIVERABLE that will satisfy this goal.
      |3. Any specific CONSTRAINTS (technical constraints, style, requirements).
      |4. Any critical ASSUMPTIONS that must be made to proceed.
      |5. Any KNOWN FACTS or inputs provided in the prompt.
      |
      |You MUST return your response as a single, valid JSON object with the following schema:
      |{
      |  "goal": "a concise, clear statement of the core goal",
      |  "deliverable": "the concrete, testable deliverable to be produced",
      |  "constraints": [
      |    "constraint 1",
      |    "constraint 2"
      |  ],
      |  "assumptions": [
      |    "assumption 1",
      |    "assumption 2"
      |  ],
      |  "knownFacts": [
      |    "known fact 1",
      |    "known fact 2"
      |  ]
      |}
      |
      |Your response MUST be valid JSON only. Do not output any XML tags, markdown blocks (like ```json), preambles, introductory or concluding text. Keep it refined and direct.
      |""".stripMargin
  }

  def userPrompt(input: String): String = {
    s"""Below is the user's initial input:
       |
       |$input
       |
       |Please generate the structured GOAL CONTRACT JSON object now.
       |""".stripMargin
  }

  // Define the given Deserializable instance to implement deserialization cleanly through typeclasses
  given Deserializable[String, GoalContract] with {
    override def deserialize(data: String): Either[Throwable, GoalContract] = {
      val trimmed = data.trim
      // Clean up markdown code blocks if the LLM outputted them despite instructions
      val cleanJson = if (trimmed.startsWith("```json")) {
        trimmed.stripPrefix("```json").stripSuffix("```").trim
      } else if (trimmed.startsWith("```")) {
        trimmed.stripPrefix("```").stripSuffix("```").trim
      } else {
        trimmed
      }

      parse(cleanJson) match {
        case Right(json) =>
          for {
            goal <- json.hcursor.get[String]("goal")
            deliverable <- json.hcursor.get[String]("deliverable")
            constraints <- json.hcursor.get[List[String]]("constraints").orElse(Right(List.empty[String]))
            assumptions <- json.hcursor.get[List[String]]("assumptions").orElse(Right(List.empty[String]))
            knownFacts <- json.hcursor.get[List[String]]("knownFacts").orElse(Right(List.empty[String]))
          } yield GoalContract(goal, deliverable, constraints, assumptions, knownFacts)

        case Left(jsonError) =>
          // Fallback to legacy line-by-line parser if JSON parsing fails
          Right(parseFallback(trimmed))
      }
    }
  }

  /**
   * Robust plain-text line-by-line parser as fallback.
   * Expects lines starting with prefixes, or sections.
   */
  def parseFallback(text: String): GoalContract = {
    val lines = text.split("\n").map(_.trim).filter(_.nonEmpty)
    
    var goal = ""
    var deliverable = ""
    var constraints = List.empty[String]
    var assumptions = List.empty[String]
    var knownFacts = List.empty[String]

    var currentSection = ""

    lines.foreach { line =>
      val upperLine = line.toUpperCase
      if (upperLine.startsWith("GOAL:")) {
        currentSection = "GOAL"
        goal = line.substring("GOAL:".length).trim
      } else if (upperLine.startsWith("DELIVERABLE:")) {
        currentSection = "DELIVERABLE"
        deliverable = line.substring("DELIVERABLE:".length).trim
      } else if (upperLine.startsWith("CONSTRAINTS:")) {
        currentSection = "CONSTRAINTS"
      } else if (upperLine.startsWith("ASSUMPTIONS:")) {
        currentSection = "ASSUMPTIONS"
      } else if (upperLine.startsWith("KNOWN_FACTS:") || upperLine.startsWith("KNOWN FACTS:")) {
        currentSection = "KNOWN_FACTS"
      } else {
        // We are inside a section and reading lines
        val cleanLine = if (line.startsWith("-") || line.startsWith("*")) line.substring(1).trim else line
        if (cleanLine.nonEmpty) {
          currentSection match {
            case "GOAL" => goal = if (goal.isEmpty) cleanLine else s"$goal $cleanLine"
            case "DELIVERABLE" => deliverable = if (deliverable.isEmpty) cleanLine else s"$deliverable $cleanLine"
            case "CONSTRAINTS" => constraints = constraints :+ cleanLine
            case "ASSUMPTIONS" => assumptions = assumptions :+ cleanLine
            case "KNOWN_FACTS" => knownFacts = knownFacts :+ cleanLine
            case _ => // default fallback to goal
              if (goal.isEmpty) goal = cleanLine else goal = s"$goal $cleanLine"
          }
        }
      }
    }

    GoalContract(
      goal = if (goal.isEmpty) "Solve user request" else goal,
      deliverable = if (deliverable.isEmpty) "Deliver completed task" else deliverable,
      constraints = constraints,
      assumptions = assumptions,
      knownFacts = knownFacts
    )
  }
}
