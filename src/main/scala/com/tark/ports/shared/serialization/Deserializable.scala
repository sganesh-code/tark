package com.tark.ports.shared.serialization

/**
 * Typeclass for deserializing data from representation R to type T,
 * possibly returning a failure.
 */
trait Deserializable[R, T] {
  def deserialize(data: R): Either[Throwable, T]
}
