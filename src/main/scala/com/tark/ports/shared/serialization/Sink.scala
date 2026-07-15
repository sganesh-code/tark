package com.tark.ports.shared.serialization

import cats.effect.IO
import java.nio.file.Path

trait Sink[F[_], T, D] {
  def write(data: T, destination: D): F[Unit]
}

object Sink {
  /**
   * Foundational Sink that writes a String to a file Path under IO.
   */
  given Sink[IO, String, Path] with {
    override def write(data: String, destination: Path): IO[Unit] = IO.blocking {
      val parent = destination.getParent
      if (parent != null && !java.nio.file.Files.exists(parent)) {
        java.nio.file.Files.createDirectories(parent)
      }
      java.nio.file.Files.writeString(destination, data)
    }.void
  }

  /**
   * Foundational Sink that appends a String to a StringBuilder under IO.
   */
  given Sink[IO, String, java.lang.StringBuilder] with {
    override def write(data: String, destination: java.lang.StringBuilder): IO[Unit] = IO {
      destination.setLength(0) // Clear previous contents
      destination.append(data)
    }.void
  }

  /**
   * Generic composite Sink that automatically bridges any Serializable[T, String] payload
   * and any Sink[F, String, D] destination.
   */
  given [F[_], T, D](using serializable: Serializable[T, String], stringSink: Sink[F, String, D]): Sink[F, T, D] with {
    override def write(data: T, destination: D): F[Unit] =
      stringSink.write(serializable.serialize(data), destination)
  }
}
