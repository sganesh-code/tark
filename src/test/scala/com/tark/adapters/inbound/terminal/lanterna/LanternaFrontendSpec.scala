package com.tark.adapters.inbound.terminal.lanterna

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
import com.tark.ports.AgentBackend
import com.tark.ui.*
import fs2.Stream
import munit.FunSuite
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.*

class LanternaFrontendSpec extends FunSuite {
  test("LanternaFrontend executes backend actions and handles input") {
    val events = ArrayBuffer.empty[String]

    given backend: AgentBackend[IO] with {
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

    given scheduler: Schedulable[IO] with {
      override def schedule(task: IO[Unit], delay: FiniteDuration, period: FiniteDuration): IO[Unit] = task
    }
    given Animatable[SpinnerFrames] = Animatable.spinnerFrames
    given config: com.tark.domain.Config = com.tark.domain.Config.default

    given status: TerminalStatus[IO] = new TerminalStatus[IO] {
      override def update(content: String): IO[Unit] = IO.unit
      override def clear(): IO[Unit] = IO.unit
      override def updatePersistent(content: String): IO[Unit] = IO.unit
      override def updatePanel(lines: Vector[String])(using F: cats.Applicative[IO]): IO[Unit] = IO.unit
      override def clearPanel()(using F: cats.Applicative[IO]): IO[Unit] = IO.unit
    }

    val terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24))
    val screen = new TerminalScreen(terminal)
    screen.startScreen()

    val program = for {
      exitRequested <- Ref.of[IO, Boolean](false)
      stateRef <- Ref.of[IO, TuiState](TuiState())
      
      writer = new TerminalWriter[IO] {
        override def printAbove(sender: String, message: String, style: TerminalStyle): IO[Unit] =
          IO.delay(events += s"above:$sender:$message").void
        override def startInline(sender: String, style: TerminalStyle): IO[Unit] = IO.unit
        override def appendInline(message: String, style: TerminalStyle): IO[Unit] = IO.unit
        override def finishInline(): IO[Unit] = IO.unit
        override def printSystemMessage(message: String, style: TerminalStyle): IO[Unit] =
          IO.delay(events += s"system:$message").void
        override def printLine(message: String): IO[Unit] =
          IO.delay(events += s"line:$message").void
        override def clearScreen(): IO[Unit] =
          IO.delay(events += "clear").void
        override def flush(): IO[Unit] = IO.unit
      }

      reader = new TerminalReader[IO] {
        override def readLine(promptPrefix: String): IO[InputResult] = IO.pure(InputResult.Exit)
        override def readChoice(prompt: String, options: List[String], allowCustom: Boolean): IO[String] =
          IO.pure(options.headOption.getOrElse(""))
      }

      spinner = new Spinner[IO, SpinnerFrames] {
        override def create(frame: SpinnerFrames, message: String)(using
          scheduler: Schedulable[IO],
          animatable: Animatable[SpinnerFrames],
          status: TerminalStatus[IO]
        ): Resource[IO, Unit] =
          Resource.make(IO.delay(events += s"spinner:$message").void)(_ => IO.delay(events += "spinner:clear").void)
      }

      frontend = LanternaFrontend(screen, stateRef, writer, reader, spinner, exitRequested)
      _ <- frontend.handleInput("hello")
      exited <- exitRequested.get
    } yield exited

    val exited = program.unsafeRunSync()

    assert(exited)
    assert(events.contains("spinner:working"))
    assert(events.contains("above:Agent:agent text"))
    assert(events.contains("system:system text"))
    assert(events.contains("clear"))
  }
}
