package com.tark.ports.outbound.backend

import com.tark.ports.shared.serialization.Deserializable
import io.circe.parser.*

object TaskPlannerPrompt {

  def systemInstructions: String = {
    """You are a systematic Task Planner. Your job is to decompose the established Goal and Deliverables into a sequential checklist of execution steps.
      |
      |You must break down the execution flow into 3 to 7 concrete, checkable, and logical steps. Each step must be clear and action-oriented.
      |
      |You MUST return your response as a single, valid JSON array of strings:
      |[
      |  "1. Step one description",
      |  "2. Step two description",
      |  "3. Step three description"
      |]
      |
      |Your response MUST be valid JSON only. Do not output any XML tags, markdown blocks (like ```json), preambles, introductory or concluding text.
      |""".stripMargin
  }

  def userPrompt(goal: String, deliverable: String, constraints: List[String]): String = {
    val constraintsStr = if (constraints.nonEmpty) constraints.map(c => s"- $c").mkString("\n") else "(None)"
    s"""Goal: $goal
       |Deliverable: $deliverable
       |Constraints:
       |$constraintsStr
       |
       |Please generate the sequential checklist plan now.
       |""".stripMargin
  }

  // Provide a clean given instance of Deserializable to parse the list of steps cleanly via JSON or line-by-line fallback
  given Deserializable[String, List[String]] with {
    override def deserialize(data: String): Either[Throwable, List[String]] = {
      val trimmed = data.trim
      // Clean up markdown code blocks if the LLM outputted them despite instructions
      val cleanJson = if (trimmed.startsWith("```json")) {
        trimmed.stripPrefix("```json").stripSuffix("```").trim
      } else if (trimmed.startsWith("```")) {
        trimmed.stripPrefix("```").stripSuffix("```").trim
      } else {
        trimmed
      }

      parse(cleanJson).flatMap(_.as[List[String]]) match {
        case Right(steps) => Right(steps.filter(_.trim.nonEmpty))
        case Left(_)      => Right(parseFallback(trimmed))
      }
    }
  }

  /**
   * Fallback line-by-line parser if JSON parsing fails.
   * Extracts bullets, dashes, or numbered lines.
   */
  def parseFallback(text: String): List[String] = {
    text.split("\n")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { line =>
        // Strip out bullet points or numbers if they are simple prefixes
        if (line.startsWith("-") || line.startsWith("*")) {
          line.substring(1).trim
        } else {
          line
        }
      }
      .filter(_.nonEmpty)
      .toList
  }
}
