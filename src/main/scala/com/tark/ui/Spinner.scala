package com.tark.ui

import cats.Applicative
import cats.effect.Resource

import scala.concurrent.duration.FiniteDuration

final case class SpinnerFrames(frames: Vector[String])

trait Animatable[A]:
  def nextIdx(frame: A, currIdx: Int): Int
  def frame(frame: A, i: Int): String

object Animatable:
  given spinnerFrames: Animatable[SpinnerFrames] with
    override def nextIdx(frame: SpinnerFrames, currIdx: Int): Int =
      if frame.frames.isEmpty then 0 else (currIdx + 1) % frame.frames.length

    override def frame(frame: SpinnerFrames, i: Int): String =
      frame.frames.lift(i).getOrElse("")

trait Schedulable[F[_]]:
  def schedule(task: F[Unit], delay: FiniteDuration, period: FiniteDuration): F[Unit]

trait TerminalStatus[F[_]]:
  def update(content: String): F[Unit]
  def clear(): F[Unit]
  def updatePersistent(content: String): F[Unit] = update(content)
  def updatePanel(lines: Vector[String])(using F: Applicative[F]): F[Unit] = F.unit
  def clearPanel()(using F: Applicative[F]): F[Unit] = F.unit

trait Spinner[F[_], A]:
  def create(frame: A, message: String)(using
    scheduler: Schedulable[F],
    animatable: Animatable[A],
    status: TerminalStatus[F]
  ): Resource[F, Unit]
