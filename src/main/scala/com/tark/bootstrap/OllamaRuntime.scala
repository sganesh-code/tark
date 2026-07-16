package com.tark.bootstrap

import cats.effect.{IO, Resource, Sync}
import com.tark.adapters.backend.ollama.{OllamaEpisodicMemorySummarizer, OllamaLlmClient}
import com.tark.ports.outbound.backend.{BackendProvider, LlmClient}
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import sttp.client3.httpclient.cats.HttpClientCatsBackend

object OllamaRuntime {
  given backendProvider(using runtimeConfig: RuntimeConfig): BackendProvider[IO] with {
    override def getClient: Resource[IO, LlmClient[IO]] = {
      HttpClientCatsBackend.resource[IO]().map { sttpBackend =>
        val config = runtimeConfig.config
        new OllamaLlmClient(sttpBackend, config.modelId, config.baseUrl)
      }
    }
  }

  given episodicMemorySummarizer[F[_]: Sync](using client: LlmClient[F]): EpisodicMemorySummarizer[F] =
    new OllamaEpisodicMemorySummarizer[F](client)
}
