package com.tark.adapters.inbound.terminal.jline

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import com.tark.ports.AgentBackend
import com.tark.ui.*
import fs2.Stream
import munit.FunSuite

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration

class JLineFrontendSpec extends FunSuite {
  test("JLineFrontend executes backend actions through terminal typeclasses") {
    val events = ArrayBuffer.empty[String]

    val program = for {
      exitRequested <- Ref.of[IO, Boolean](false)
      frontend = {
        given AgentBackend[IO] with {
          override def registerCompletions(update: List[String] => IO[Unit]): IO[Unit] = IO.unit
          override def handleInput(input: String): Stream[IO, AgentTask[IO]] =
            Stream.emit(
              AgentTask(
                Some("working"),
                Stream(
                  AgentAction.Log("agent text"),
                  AgentAction.SystemMessage("system text"),
                  AgentAction.ClearScreen(),
                  AgentAction.Exit()
                )
              )
            )
        }

        given Schedulable[IO] with {
          override def schedule(task: IO[Unit], delay: FiniteDuration, period: FiniteDuration): IO[Unit] = task
        }

        given Animatable[SpinnerFrames] = Animatable.spinnerFrames
        given TerminalStatus[IO] with {
          override def update(content: String): IO[Unit] = IO.delay(events += s"status:$content").void
          override def clear(): IO[Unit] = IO.delay(events += "status:clear").void
        }

        val writer = new TerminalWriter[IO] {
          override def printAbove(sender: String, message: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"above:$sender:$message").void
          override def startInline(sender: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"inline-start:$sender").void
          override def appendInline(message: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"inline:$message").void
          override def finishInline(): IO[Unit] =
            IO.delay(events += "inline-end").void
          override def printSystemMessage(message: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"system:$message").void
          override def printLine(message: String): IO[Unit] =
            IO.delay(events += s"line:$message").void
          override def clearScreen(): IO[Unit] =
            IO.delay(events += "clear").void
          override def flush(): IO[Unit] =
            IO.delay(events += "flush").void
        }

        val reader = new TerminalReader[IO] {
          override def readLine(promptPrefix: String): IO[InputResult] = IO.pure(InputResult.Exit)
          override def readChoice(prompt: String, options: List[String], allowCustom: Boolean): IO[String] =
            IO.pure(options.headOption.getOrElse(""))
        }

        val spinner = new Spinner[IO, SpinnerFrames] {
          override def create(frame: SpinnerFrames, message: String)(using
            scheduler: Schedulable[IO],
            animatable: Animatable[SpinnerFrames],
            status: TerminalStatus[IO]
          ): Resource[IO, Unit] =
            Resource.make(IO.delay(events += s"spinner:$message").void)(_ => IO.delay(events += "spinner:clear").void)
        }

        JLineFrontend(writer, reader, spinner, exitRequested)
      }
      _ <- frontend.handleInput("hello")
      exited <- exitRequested.get
    } yield exited

    val exited = program.unsafeRunSync()

    assert(exited)
    assert(events.contains("spinner:working"))
    assert(events.contains("above:Agent:agent text"))
    assert(events.contains("system:system text"))
    assert(events.contains("clear"))
    assert(events.contains("flush"))
  }

  test("JLineFrontend renders assistant deltas as one inline message") {
    val events = ArrayBuffer.empty[String]

    val program = for {
      exitRequested <- Ref.of[IO, Boolean](false)
      frontend = {
        given AgentBackend[IO] with {
          override def registerCompletions(update: List[String] => IO[Unit]): IO[Unit] = IO.unit
          override def handleInput(input: String): Stream[IO, AgentTask[IO]] =
            Stream.emit(
              AgentTask(
                None,
                Stream(
                  AgentAction.AssistantDelta("Now"),
                  AgentAction.AssistantDelta(" let"),
                  AgentAction.AssistantDelta(" me"),
                  AgentAction.AssistantEnd()
                )
              )
            )
        }

        given Schedulable[IO] with {
          override def schedule(task: IO[Unit], delay: FiniteDuration, period: FiniteDuration): IO[Unit] = task
        }

        given Animatable[SpinnerFrames] = Animatable.spinnerFrames
        given TerminalStatus[IO] with {
          override def update(content: String): IO[Unit] = IO.unit
          override def clear(): IO[Unit] = IO.unit
        }

        val writer = new TerminalWriter[IO] {
          override def printAbove(sender: String, message: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"above:$sender:$message").void
          override def startInline(sender: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"inline-start:$sender").void
          override def appendInline(message: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"inline:$message").void
          override def finishInline(): IO[Unit] =
            IO.delay(events += "inline-end").void
          override def printSystemMessage(message: String, style: TerminalStyle): IO[Unit] =
            IO.delay(events += s"system:$message").void
          override def printLine(message: String): IO[Unit] = IO.unit
          override def clearScreen(): IO[Unit] = IO.unit
          override def flush(): IO[Unit] = IO.unit
        }

        val reader = new TerminalReader[IO] {
          override def readLine(promptPrefix: String): IO[InputResult] = IO.pure(InputResult.Exit)
          override def readChoice(prompt: String, options: List[String], allowCustom: Boolean): IO[String] =
            IO.pure(options.headOption.getOrElse(""))
        }

        val spinner = new Spinner[IO, SpinnerFrames] {
          override def create(frame: SpinnerFrames, message: String)(using
            scheduler: Schedulable[IO],
            animatable: Animatable[SpinnerFrames],
            status: TerminalStatus[IO]
          ): Resource[IO, Unit] =
            Resource.unit
        }

        JLineFrontend(writer, reader, spinner, exitRequested)
      }
      _ <- frontend.handleInput("hello")
    } yield ()

    program.unsafeRunSync()

    assertEquals(
      events.toList,
      List("inline-start:Agent", "inline:Now", "inline: let", "inline: me", "inline-end")
    )
  }
}
