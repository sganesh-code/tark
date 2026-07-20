package com.tark.bootstrap

import cats.effect.{IO, Resource, Sync}
import com.tark.adapters.backend.ollama.{OllamaEpisodicMemorySummarizer, OllamaGoalContractParser, OllamaLlmClient, OllamaTaskPlanner, OllamaPlanVerifier}
import com.tark.ports.outbound.backend.{BackendProvider, GoalContractParser, LlmClient, TaskPlanner, PlanVerifier}
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.domain.GoalContract
import sttp.client3.asynchttpclient.fs2.AsyncHttpClientFs2Backend

object OllamaRuntime {
  given backendProvider(using runtimeConfig: RuntimeConfig): BackendProvider[IO] with {
    override def getClient: Resource[IO, LlmClient[IO]] = {
      AsyncHttpClientFs2Backend.resource[IO]().map { sttpBackend =>
        val config = runtimeConfig.config
        new OllamaLlmClient(sttpBackend, config.modelId, config.baseUrl)
      }
    }
  }

  given episodicMemorySummarizer[F[_]: Sync](using client: LlmClient[F]): EpisodicMemorySummarizer[F] =
    new OllamaEpisodicMemorySummarizer[F](client)

  given goalContractParser[F[_]: Sync](using client: LlmClient[F]): GoalContractParser[F] =
    new OllamaGoalContractParser[F](client)

  given taskPlanner[F[_]: Sync](using client: LlmClient[F]): TaskPlanner[F, GoalContract] =
    new OllamaTaskPlanner[F](client)

  given planVerifier[F[_]: Sync](using client: LlmClient[F]): PlanVerifier[F, GoalContract] =
    new OllamaPlanVerifier[F](client)
}
