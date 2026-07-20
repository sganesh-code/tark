package com.tark.adapters.backend.ollama

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.GoalContract
import com.tark.ports.outbound.backend.{LlmClient, Prompt, TaskPlanner, TaskPlannerPrompt}
import com.tark.domain.tool.OpenAIMessage
import com.tark.ports.shared.serialization.Deserializable

/**
 * Concrete implementation of the generalized TaskPlanner typeclass that uses
 * Ollama LlmClient to decompose a GoalContract into sequential execution steps.
 */
class OllamaTaskPlanner[F[_]: Sync](client: LlmClient[F]) extends TaskPlanner[F, GoalContract] {

  override def generatePlan(contract: GoalContract): F[List[String]] = {
    val systemPrompt = TaskPlannerPrompt.systemInstructions
    val userPrompt = TaskPlannerPrompt.userPrompt(contract)
    val F = summon[Sync[F]]

    val prompt = Prompt(
      messages = List(
        OpenAIMessage(role = "system", content = Some(systemPrompt)),
        OpenAIMessage(role = "user", content = Some(userPrompt))
      ),
      availableTools = List.empty
    )

    client.chat(prompt).flatMap { response =>
      import TaskPlannerPrompt.given
      val deserializer = summon[Deserializable[String, List[String]]]
      deserializer.deserialize(response.content) match {
        case Right(steps) =>
          F.pure(steps)
        case Left(error) =>
          F.raiseError(new RuntimeException(s"Failed to parse task plan: ${error.getMessage}", error))
      }
    }
  }
}
