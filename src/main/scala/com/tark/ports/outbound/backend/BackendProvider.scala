package com.tark.ports.outbound.backend

import cats.effect.Resource

trait BackendProvider[F[_]] {
  def getClient: Resource[F, LlmClient[F]]
}
