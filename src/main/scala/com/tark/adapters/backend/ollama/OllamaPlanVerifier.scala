package com.tark.adapters.backend.ollama

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.GoalContract
import com.tark.ports.outbound.backend.{LlmClient, Prompt, PlanVerifier, PlanVerifierPrompt}
import com.tark.domain.tool.OpenAIMessage
import com.tark.ports.shared.serialization.Deserializable

/**
 * Concrete implementation of the PlanVerifier port that uses
 * Ollama LlmClient to perform a self-critique pass on generated task plans.
 */
class OllamaPlanVerifier[F[_]: Sync](client: LlmClient[F]) extends PlanVerifier[F, GoalContract] {

  override def verifyPlan(contract: GoalContract, plan: List[String]): F[Boolean] = {
    val systemPrompt = PlanVerifierPrompt.systemInstructions
    val userPrompt = PlanVerifierPrompt.userPrompt(contract, plan)
    val F = summon[Sync[F]]

    val prompt = Prompt(
      messages = List(
        OpenAIMessage(role = "system", content = Some(systemPrompt)),
        OpenAIMessage(role = "user", content = Some(userPrompt))
      ),
      availableTools = List.empty
    )

    client.chat(prompt).flatMap { response =>
      import PlanVerifierPrompt.given
      val deserializer = summon[Deserializable[String, Boolean]]
      deserializer.deserialize(response.content) match {
        case Right(valid) =>
          F.pure(valid)
        case Left(error) =>
          F.raiseError(new RuntimeException(s"Failed to parse plan validation: ${error.getMessage}", error))
      }
    }
  }
}
