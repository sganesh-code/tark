package com.tark.ports.outbound.ui

import cats.effect.IO

import java.io.PrintWriter

trait ScreenWriterF[F[_], A] {
  def write(screen: A, out: PrintWriter): F[Unit]
  def writeDelta(screen: A, lastScreen: A, out: PrintWriter): F[Unit] =
    write(screen, out)
}

object ScreenWriterF {
  def fromScreenWriter[A](writer: ScreenWriter[A]): ScreenWriterF[IO, A] =
    new ScreenWriterF[IO, A] {
      override def write(screen: A, out: PrintWriter): IO[Unit] =
        writer.write(screen, out)

      override def writeDelta(screen: A, lastScreen: A, out: PrintWriter): IO[Unit] =
        writer.writeDelta(screen, lastScreen, out)
    }
}
