package com.tark.bootstrap

import cats.effect.{IO, Resource, Sync}
import com.tark.adapters.backend.ollama.{
  OllamaEpisodicMemorySummarizer,
  OllamaLlmClient,
  OllamaMessage,
  OllamaReActLlmClient,
  OllamaReActStrategy,
  OllamaRequest,
  OllamaResponse
}
import com.tark.ports.outbound.backend.{BackendProvider, LlmClient}
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.react.{ReActLlmClient, ReActStrategy}
import sttp.client3.httpclient.cats.HttpClientCatsBackend

object OllamaRuntime {
  given backendProvider(using runtimeConfig: RuntimeConfig): BackendProvider[IO] with {
    override def getClients: Resource[IO, (LlmClient[IO], ReActLlmClient[IO])] = {
      HttpClientCatsBackend.resource[IO]().map { sttpBackend =>
        val config = runtimeConfig.config
        val llm = new OllamaLlmClient(sttpBackend, config.modelId, config.baseUrl)
        given strategy: ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] =
          new OllamaReActStrategy.JsonReActStrategy()
        val react = new OllamaReActLlmClient(sttpBackend, config.modelId, config.baseUrl)
        (llm, react)
      }
    }
  }

  given episodicMemorySummarizer[F[_]: Sync](using client: LlmClient[F]): EpisodicMemorySummarizer[F] =
    new OllamaEpisodicMemorySummarizer[F](client)
}
