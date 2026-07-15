package com.tark.adapters.backend.ollama

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.Interaction
import com.tark.domain.memory.EpisodeSummary
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.outbound.memory.{EpisodicMemorySummarizer, MemoryPrompt}

/**
 * Concrete implementation of EpisodicMemorySummarizer that uses standard LlmClient
 * to perform text-based summarization of session history.
 */
class OllamaEpisodicMemorySummarizer[F[_]: Sync](client: LlmClient[F]) extends EpisodicMemorySummarizer[F] {

  override def summarize(sessionId: String, history: List[Interaction]): F[EpisodeSummary] = {
    val formattedHistory = MemoryPrompt.formatHistory(history)
    val systemPrompt = MemoryPrompt.summarizationSystemPrompt
    val userPrompt = MemoryPrompt.summarizationUserPrompt(formattedHistory)
    val F = summon[Sync[F]]

    client.getCompletion(userPrompt, List.empty, systemPrompt, List.empty).flatMap {
      case Left(textResponse) =>
        val (summary, takeaways) = MemoryPrompt.parseSummarizerOutput(textResponse)
        for {
          now <- F.delay(System.currentTimeMillis())
        } yield EpisodeSummary(sessionId, now, summary, takeaways)

      case Right(_) =>
        // Fallback in case the LLM returned a tool call unexpectedly during summarization
        for {
          now <- F.delay(System.currentTimeMillis())
        } yield EpisodeSummary(
          sessionId = sessionId,
          timestamp = now,
          summary = "Failed to summarize: LLM returned a tool call instead of raw text.",
          keyTakeaways = List.empty
        )
    }
  }
}
