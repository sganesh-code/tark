package com.tark.ports.outbound.context

import cats.effect.Resource
import com.tark.domain.context.Session

trait SessionProvider[F[_]] {
  def createSession: Resource[F, Session]
}
