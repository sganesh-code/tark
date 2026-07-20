package com.tark.adapters.inbound.terminal.lanterna

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.tark.ui.*
import scala.concurrent.duration.*

final class LanternaSpinner(
  stateRef: cats.effect.Ref[IO, TuiState],
  redraw: IO[Unit],
  delay: FiniteDuration,
  period: FiniteDuration
) extends Spinner[IO, SpinnerFrames] {

  override def create(frame: SpinnerFrames, message: String)(using
    scheduler: Schedulable[IO],
    animatable: Animatable[SpinnerFrames],
    status: TerminalStatus[IO]
  ): Resource[IO, Unit] = {
    @volatile var frameIdx = 0
    val task =
      IO.defer {
        val currentFrame = animatable.frame(frame, frameIdx)
        stateRef.update(_.copy(
          spinnerFrame = currentFrame,
          statusText = message
        )) >> redraw >> IO.delay {
          frameIdx = animatable.nextIdx(frame, frameIdx)
        }
      }
    Resource.make(scheduler.schedule(task, delay, period).start)(fiber => 
      fiber.cancel >> stateRef.update(_.copy(spinnerFrame = "", statusText = "")) >> redraw
    ).void
  }
}
