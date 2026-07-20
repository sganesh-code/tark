package com.tark.adapters.inbound.terminal.lanterna

import cats.Applicative
import cats.effect.{IO, Ref}
import com.googlecode.lanterna.screen.Screen
import com.tark.ui.TerminalStatus

final class LanternaTerminalStatus(
  screen: Screen,
  stateRef: Ref[IO, TuiState]
) extends TerminalStatus[IO] {

  private def redraw: IO[Unit] =
    stateRef.get.flatMap { state =>
      IO.blocking(LanternaTuiRenderer.render(screen, state))
    }

  override def update(content: String): IO[Unit] =
    stateRef.update(_.copy(statusText = content)) >> redraw

  override def clear(): IO[Unit] =
    stateRef.update(_.copy(statusText = "", spinnerFrame = "")) >> redraw

  override def updatePersistent(content: String): IO[Unit] =
    stateRef.update(_.copy(statusText = content)) >> redraw

  override def updatePanel(lines: Vector[String])(using F: Applicative[IO]): IO[Unit] =
    stateRef.update(_.copy(activePanelLines = lines)) >> redraw

  override def clearPanel()(using F: Applicative[IO]): IO[Unit] =
    stateRef.update(_.copy(activePanelLines = Vector.empty)) >> redraw
}
