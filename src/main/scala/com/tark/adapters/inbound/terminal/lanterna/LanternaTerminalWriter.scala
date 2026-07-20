package com.tark.adapters.inbound.terminal.lanterna

import cats.effect.{IO, Ref}
import com.googlecode.lanterna.screen.Screen
import com.tark.ui.{TerminalStyle, TerminalWriter}

final class LanternaTerminalWriter(
  screen: Screen,
  stateRef: Ref[IO, TuiState]
) extends TerminalWriter[IO] {

  private def redraw: IO[Unit] =
    stateRef.get.flatMap { state =>
      IO.blocking(LanternaTuiRenderer.render(screen, state))
    }

  override def printAbove(sender: String, message: String, style: TerminalStyle): IO[Unit] =
    stateRef.update { s =>
      s.copy(
        scrollback = s.scrollback :+ LanternaLogLine(Some(sender), message, style),
        inlineOpen = false
      )
    } >> redraw

  override def startInline(sender: String, style: TerminalStyle): IO[Unit] =
    stateRef.update { s =>
      s.copy(
        scrollback = s.scrollback :+ LanternaLogLine(Some(sender), "", style),
        inlineOpen = true
      )
    } >> redraw

  override def appendInline(message: String, style: TerminalStyle): IO[Unit] =
    stateRef.update { s =>
      val updatedScrollback = if (s.scrollback.nonEmpty) {
        val lastLine = s.scrollback.last
        val updatedLastLine = lastLine.copy(text = lastLine.text + message)
        s.scrollback.updated(s.scrollback.size - 1, updatedLastLine)
      } else {
        s.scrollback :+ LanternaLogLine(None, message, style)
      }
      s.copy(scrollback = updatedScrollback)
    } >> redraw

  override def finishInline(): IO[Unit] =
    stateRef.update(_.copy(inlineOpen = false)) >> redraw

  override def printSystemMessage(message: String, style: TerminalStyle): IO[Unit] =
    stateRef.update { s =>
      s.copy(
        scrollback = s.scrollback :+ LanternaLogLine(Some("System"), message, style),
        inlineOpen = false
      )
    } >> redraw

  override def printLine(message: String): IO[Unit] =
    stateRef.update { s =>
      s.copy(
        scrollback = s.scrollback :+ LanternaLogLine(None, message, TerminalStyle.Default),
        inlineOpen = false
      )
    } >> redraw

  override def clearScreen(): IO[Unit] =
    stateRef.update(_.copy(scrollback = Vector.empty, inlineOpen = false)) >> redraw

  override def flush(): IO[Unit] =
    redraw
}
