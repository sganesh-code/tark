package com.tark.ports.shared.serialization

trait Serializable[T, R] {
  def serialize(data: T): R
}
