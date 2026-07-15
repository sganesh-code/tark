package com.tark.adapters.ui

import cats.effect.IO
import com.tark.domain.ui.{Cell, Screen}
import com.tark.ports.outbound.ui.ScreenWriter
import com.tark.ports.shared.ui.CellRenderer

import java.io.PrintWriter

object ScreenWriterInstances {
  given (using renderer: CellRenderer[Cell, String]): ScreenWriter[Screen] with
    override def write(screen: Screen, out: PrintWriter): IO[Unit] = IO {
      out.print("\u001b[H")
      out.print("\u001b[2J")

      for {
        y <- 0 until screen.height
      } {
        out.print(s"\u001b[${y + 1};1H")
        for {
          x <- 0 until screen.width
        } {
          out.print(renderer.render(screen.cell(x, y)))
        }
      }
      out.flush()
    }

    override def writeDelta(screen: Screen, lastScreen: Screen, out: PrintWriter): IO[Unit] = IO {
      var lastX = -2
      var lastY = -2
      for {
        y <- 0 until screen.height
        x <- 0 until screen.width
        c = screen.cell(x, y)
        if c != lastScreen.cell(x, y)
      } {
        if (y != lastY || x != lastX + 1) {
          out.print(s"\u001b[${y + 1};${x + 1}H")
        }
        out.print(renderer.render(c))
        lastX = x
        lastY = y
      }
      out.flush()
    }
}
