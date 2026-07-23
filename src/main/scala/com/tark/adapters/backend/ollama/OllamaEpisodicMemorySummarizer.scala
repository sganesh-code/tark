package com.tark.adapters.backend.ollama

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.{Interaction, Prompt}
import com.tark.domain.memory.EpisodeSummary
import com.tark.domain.tool.OpenAIMessage
import com.tark.ports.outbound.backend.{LlmClient}
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

    val prompt = Prompt(
      messages = List(
        OpenAIMessage(role = "system", content = Some(systemPrompt)),
        OpenAIMessage(role = "user", content = Some(userPrompt))
      ),
      availableTools = List.empty
    )

    client.chat(prompt).flatMap { response =>
      val (summary, takeaways) = MemoryPrompt.parseSummarizerOutput(response.content)
      for {
        now <- F.delay(System.currentTimeMillis())
      } yield EpisodeSummary(sessionId, now, summary, takeaways)
    }
  }
}
