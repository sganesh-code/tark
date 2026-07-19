package com.tark.bootstrap

import cats.effect.{IO, Resource, Sync}
import com.tark.adapters.backend.ollama.{OllamaEpisodicMemorySummarizer, OllamaGoalContractParser, OllamaLlmClient}
import com.tark.ports.outbound.backend.{BackendProvider, GoalContractParser, LlmClient}
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
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
}
