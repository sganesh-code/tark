package com.tark.ports.outbound.ui

import cats.effect.IO

import java.io.PrintWriter

trait ScreenWriter[A] {
  def write(screen: A, out: PrintWriter): IO[Unit]
  def writeDelta(screen: A, lastScreen: A, out: PrintWriter): IO[Unit] = write(screen, out)
}
