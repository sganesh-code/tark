package com.tark.ports.outbound.backend

import cats.effect.Resource
import com.tark.ports.outbound.react.ReActLlmClient

trait BackendProvider[F[_]] {
  def getClients: Resource[F, (LlmClient[F], ReActLlmClient[F])]
}
