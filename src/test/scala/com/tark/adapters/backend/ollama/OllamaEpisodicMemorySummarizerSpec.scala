package com.tark.adapters.backend.ollama

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.Interaction
import com.tark.domain.memory.EpisodeSummary
import com.tark.domain.tool.{Tool, ToolCallRequest}
import com.tark.ports.outbound.backend.LlmClient
import munit.FunSuite

class OllamaEpisodicMemorySummarizerSpec extends FunSuite {
  test("OllamaEpisodicMemorySummarizer: summarizes history successfully using LlmClient") {
    val expectedText =
      """
        |SUMMARY: Completed the tasks.
        |TAKEAWAYS:
        |- Takeaway 1
      """.stripMargin

    val mockLlm = new LlmClient[IO] {
      override def getCompletion(
        prompt: String,
        history: List[Interaction],
        systemPrompt: String,
        tools: List[Tool]
      ): IO[Either[String, List[ToolCallRequest]]] = IO {
        assert(prompt.contains("Please generate the structured SUMMARY and TAKEAWAYS"))
        assert(systemPrompt.contains("You are a meticulous session-summarizing agent."))
        Left(expectedText)
      }
    }

    val summarizer = new OllamaEpisodicMemorySummarizer[IO](mockLlm)
    val history = List(Interaction("1", "input", "output", 1000L, "tool"))

    val result: EpisodeSummary = summarizer.summarize("session_123", history).unsafeRunSync()
    assertEquals(result.sessionId, "session_123")
    assertEquals(result.summary, "Completed the tasks.")
    assertEquals(result.keyTakeaways, List("Takeaway 1"))
    assert(result.timestamp > 0L)
  }

  test("OllamaEpisodicMemorySummarizer: falls back gracefully on unexpected tool calls") {
    val mockLlm = new LlmClient[IO] {
      override def getCompletion(
        prompt: String,
        history: List[Interaction],
        systemPrompt: String,
        tools: List[Tool]
      ): IO[Either[String, List[ToolCallRequest]]] = IO {
        Right(List(ToolCallRequest("some_tool", Map.empty)))
      }
    }

    val summarizer = new OllamaEpisodicMemorySummarizer[IO](mockLlm)
    val result = summarizer.summarize("session_123", List.empty).unsafeRunSync()

    assertEquals(result.sessionId, "session_123")
    assert(result.summary.contains("Failed to summarize"))
    assertEquals(result.keyTakeaways, List.empty)
  }
}
