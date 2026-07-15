package com.tark.ports.outbound.memory

import com.tark.domain.Interaction
import munit.FunSuite

class MemoryPromptSpec extends FunSuite {
  test("MemoryPrompt: formatHistory formats interaction list correctly") {
    val history = List(
      Interaction("1", "run command 'ls'", "file1.txt\nfile2.txt", 1000L, "command_executor"),
      Interaction("2", "explain project", "This is a Scala project.", 2000L, "llm_completion")
    )
    val formatted = MemoryPrompt.formatHistory(history)

    assert(formatted.contains("[Interaction 1]"))
    assert(formatted.contains("Time: 1000"))
    assert(formatted.contains("Tool: command_executor"))
    assert(formatted.contains("Input: run command 'ls'"))
    assert(formatted.contains("Output: file1.txt\nfile2.txt"))
    assert(formatted.contains("[Interaction 2]"))
    assert(formatted.contains("Tool: llm_completion"))
  }

  test("MemoryPrompt: formatHistory handles empty interaction list") {
    val formatted = MemoryPrompt.formatHistory(List.empty)
    assertEquals(formatted, "No interactions recorded in this session.")
  }

  test("MemoryPrompt: parseSummarizerOutput parses structured LLM text output successfully") {
    val llmOutput =
      """
        |SUMMARY: The user wanted to build a Scala project. We created memory data models and compiled successfully.
        |TAKEAWAYS:
        |- Prefers functional programming patterns
        |- Used munit for unit testing
        |- Skip git commits if repository not found
      """.stripMargin

    val (summary, takeaways) = MemoryPrompt.parseSummarizerOutput(llmOutput)
    assertEquals(summary, "The user wanted to build a Scala project. We created memory data models and compiled successfully.")
    assertEquals(takeaways, List("Prefers functional programming patterns", "Used munit for unit testing", "Skip git commits if repository not found"))
  }

  test("MemoryPrompt: parseSummarizerOutput handles multiple line summaries and bulletless takeaways") {
    val llmOutput =
      """
        |SUMMARY: First line of summary.
        |Second line of summary.
        |TAKEAWAYS:
        |Indented line without bullet point
        |- Bulleted takeaway
      """.stripMargin

    val (summary, takeaways) = MemoryPrompt.parseSummarizerOutput(llmOutput)
    assertEquals(summary, "First line of summary. Second line of summary.")
    assertEquals(takeaways, List("Indented line without bullet point", "Bulleted takeaway"))
  }
}
