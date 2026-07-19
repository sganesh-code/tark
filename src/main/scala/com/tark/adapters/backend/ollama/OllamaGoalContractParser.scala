package com.tark.adapters.backend.ollama

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.GoalContract
import com.tark.ports.outbound.backend.{GoalContractParser, GoalContractPrompt, LlmClient, Prompt}
import com.tark.ports.outbound.backend.GoalContractPrompt.given
import com.tark.domain.tool.OpenAIMessage
import com.tark.ports.shared.serialization.Deserializable

/**
 * Concrete implementation of GoalContractParser that uses Ollama LlmClient
 * to extract structured GoalContracts from user input.
 */
class OllamaGoalContractParser[F[_]: Sync](client: LlmClient[F]) extends GoalContractParser[F] {

  override def parseGoal(input: String): F[GoalContract] = {
    val systemPrompt = GoalContractPrompt.systemInstructions
    val userPrompt = GoalContractPrompt.userPrompt(input)
    val F = summon[Sync[F]]

    val prompt = Prompt(
      messages = List(
        OpenAIMessage(role = "system", content = Some(systemPrompt)),
        OpenAIMessage(role = "user", content = Some(userPrompt))
      ),
      availableTools = List.empty
    )

    client.chat(prompt).flatMap { response =>
      val deserializer = summon[Deserializable[String, GoalContract]]
      deserializer.deserialize(response.content) match {
        case Right(contract) =>
          F.pure(contract)
        case Left(error) =>
          F.raiseError(new RuntimeException(s"Failed to parse GoalContract: ${error.getMessage}", error))
      }
    }
  }
}
