package com.tark.adapters.backend.ollama

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.ProgressContext
import com.tark.ports.outbound.backend.{LlmClient, Prompt, ProgressTracker, ProgressTrackerPrompt}
import com.tark.domain.tool.OpenAIMessage
import com.tark.ports.shared.serialization.Deserializable

/**
 * Concrete implementation of the ProgressTracker port that uses
 * Ollama LlmClient to evaluate whether the active step has been completed.
 */
class OllamaProgressTracker[F[_]: Sync](client: LlmClient[F]) extends ProgressTracker[F] {

  override def evaluateProgress(context: ProgressContext): F[Boolean] = {
    val systemPrompt = ProgressTrackerPrompt.systemInstructions
    val userPrompt = ProgressTrackerPrompt.userPrompt(context)
    val F = summon[Sync[F]]

    val prompt = Prompt(
      messages = List(
        OpenAIMessage(role = "system", content = Some(systemPrompt)),
        OpenAIMessage(role = "user", content = Some(userPrompt))
      ),
      availableTools = List.empty
    )

    client.chat(prompt).flatMap { response =>
      import ProgressTrackerPrompt.given
      val deserializer = summon[Deserializable[String, Boolean]]
      deserializer.deserialize(response.content) match {
        case Right(completed) =>
          F.pure(completed)
        case Left(error) =>
          F.raiseError(new RuntimeException(s"Failed to parse progress evaluation: ${error.getMessage}", error))
      }
    }
  }
}
