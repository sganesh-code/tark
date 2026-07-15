package com.tark.application.time

import cats.effect.Sync

trait Clock[F[_]] {
  def realTimeMillis: F[Long]
}

object Clock {
  given system[F[_]: Sync]: Clock[F] with {
    override def realTimeMillis: F[Long] =
      Sync[F].delay(System.currentTimeMillis())
  }
}
