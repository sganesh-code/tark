package com.tark.ports.outbound.memory

import com.tark.domain.Interaction
import io.circe.parser.*

object MemoryPrompt {

  /**
   * Formats a list of interactions into a clear, chronological text representation.
   */
  def formatHistory(history: List[Interaction]): String = {
    if (history.isEmpty) {
      "No interactions recorded in this session."
    } else {
      history.zipWithIndex.map { case (interaction, idx) =>
        s"""[Interaction ${idx + 1}]
           |Time: ${interaction.timestamp}
           |Tool: ${interaction.toolName}
           |Input: ${interaction.input}
           |Output: ${interaction.output}""".stripMargin
      }.mkString("\n\n")
    }
  }

  /**
   * The system prompt used to instruct the LLM to produce a highly structured JSON summary and takeaways.
   */
  def summarizationSystemPrompt: String = {
    """You are a meticulous session-summarizing agent. Your job is to analyze the history of interactions in an LLM agent harness session and produce a clean, distilled, and highly structured summary.
      |
      |Analyze the inputs and outputs, identifying:
      |1. What the user's overarching goal was.
      |2. What steps were taken, and what final results or solutions were achieved.
      |3. Any specific user preferences (e.g., visual styles, tool choices) or constraints discovered.
      |4. Any specific tool failures, retries, or execution errors.
      |
      |You MUST return your response as a single, valid JSON object with the following schema:
      |{
      |  "summary": "a concise 2-4 sentence summary of the session goals, actions, and results",
      |  "takeaways": [
      |    "takeaway 1, user preference, decision, failure, or fact",
      |    "takeaway 2, user preference, decision, failure, or fact"
      |  ]
      |}
      |
      |Your response MUST be valid JSON only. Do not output any XML tags, preambles, introductory or concluding text. Keep it refined but completely true to what happened.
      |""".stripMargin
  }

  /**
   * The user prompt containing the formatted chronological interaction log.
   */
  def summarizationUserPrompt(formattedHistory: String): String = {
    s"""Below is the raw chronological history of interactions in this session:
       |
       |$formattedHistory
       |
       |Please generate the structured SUMMARY and TAKEAWAYS JSON object now.
       |""".stripMargin
  }

  /**
   * Helper to parse the raw text output of the LLM into a tuple of (summary, keyTakeaways).
   * Parses JSON natively and falls back to legacy text parser on JSON parse failures.
   */
  def parseSummarizerOutput(text: String): (String, List[String]) = {
    parse(text.trim) match {
      case Right(json) =>
        val summary = json.hcursor.get[String]("summary").getOrElse("Session history completed successfully.")
        val takeaways = json.hcursor.get[List[String]]("takeaways").getOrElse(List.empty)
        (summary, takeaways)
      case Left(_) =>
        // Fallback to legacy plain text line-by-line parser
        val lines = text.split("\n").map(_.trim).filter(_.nonEmpty)
        
        var summaryOpt: Option[String] = None
        var takeaways: List[String] = List.empty
        var inTakeawaysSection = false

        lines.foreach { line =>
          if (line.startsWith("SUMMARY:")) {
            inTakeawaysSection = false
            summaryOpt = Some(line.substring("SUMMARY:".length).trim)
          } else if (line.startsWith("TAKEAWAYS:")) {
            inTakeawaysSection = true
          } else if (inTakeawaysSection && line.startsWith("-")) {
            takeaways = takeaways :+ line.substring(1).trim
          } else if (inTakeawaysSection) {
            takeaways = takeaways :+ line
          } else if (summaryOpt.isDefined) {
            summaryOpt = summaryOpt.map(s => s"$s $line")
          }
        }

        val summary = summaryOpt.getOrElse("Session history completed successfully.")
        (summary, takeaways)
    }
  }
}
