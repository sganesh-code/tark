package com.tark.ports.shared.algebra

trait Route[F[_], I, S] {
  def matches(input: I): Boolean
  def run(input: I, state: S): F[S]
}

trait Dispatcher[F[_], I, S] {
  def dispatch(input: I, state: S): F[S]
}

object Dispatcher {
  def firstMatch[F[_], I, S](
    routes: List[Route[F, I, S]],
    fallback: Route[F, I, S]
  ): Dispatcher[F, I, S] =
    (input: I, state: S) => routes.find(_.matches(input)).getOrElse(fallback).run(input, state)
}
