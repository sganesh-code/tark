package com.tark.adapters.inbound.terminal.lanterna

import cats.effect.{IO, Resource}
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory

object LanternaScreenLifecycle {
  def resource: Resource[IO, Screen] =
    Resource.make {
      IO.blocking {
        val terminalFactory = new DefaultTerminalFactory()
        val screen = terminalFactory.createScreen()
        screen.startScreen()
        screen
      }
    } { screen =>
      IO.blocking {
        screen.stopScreen()
      }.handleErrorWith(err => IO.println(s"Failed to stop Lanterna screen: ${err.getMessage}"))
    }
}
