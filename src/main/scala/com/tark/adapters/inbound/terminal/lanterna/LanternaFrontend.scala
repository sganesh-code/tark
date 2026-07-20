package com.tark.adapters.inbound.terminal.lanterna

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import com.tark.ports.AgentBackend
import com.tark.ui.*
import com.tark.domain.Config
import fs2.Stream
import com.googlecode.lanterna.screen.Screen
import scala.concurrent.duration.*

final class LanternaFrontend(
  screen: Screen,
  stateRef: Ref[IO, TuiState],
  writer: TerminalWriter[IO],
  reader: TerminalReader[IO],
  spinner: Spinner[IO, SpinnerFrames],
  exitRequested: Ref[IO, Boolean]
)(using
  backend: AgentBackend[IO],
  scheduler: Schedulable[IO],
  animatable: Animatable[SpinnerFrames],
  status: TerminalStatus[IO],
  config: Config = Config.default
) extends AgentFrontend[IO] {

  private val frames = SpinnerFrames(Vector("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"))

  override def handleInput(input: String)(using backend: AgentBackend[IO]): IO[Unit] = {
    val cleanInput = input.trim
    if (cleanInput.isEmpty) IO.unit
    else {
      status.clearPanel() >>
        backend.handleInput(cleanInput).evalMap { task =>
          task.description match {
            case Some(description) =>
              spinner.create(frames, description).use(_ => executeActions(task.action))
            case None =>
              executeActions(task.action)
          }
        }.compile.drain.handleErrorWith { error =>
          writer.printAbove("System", s"Error: ${error.getMessage}", TerminalStyle.Error)
        }
    }
  }

  private def executeActions(actions: Stream[IO, AgentAction[IO]]): IO[Unit] = {
    for {
      panelStateRef <- Ref.of[IO, Option[PanelState]](None)
      rWidth <- IO.blocking {
        val size = screen.getTerminalSize
        val width = size.getColumns
        val splitCol = (width * 0.65).toInt
        width - splitCol - 2
      }
      panelConfig = PanelConfig(
        width = rWidth,
        borderStyle = BorderStyle.fromString(config.panelBorder),
        maxLines = 15
      )
      inlineOpen <- Ref.of[IO, Boolean](false)
      _ <- {
        def closeInline: IO[Unit] =
          inlineOpen.get.ifM(writer.finishInline() >> inlineOpen.set(false), IO.unit)

        def appendAgentDelta(text: String): IO[Unit] =
          inlineOpen.get.ifM(
            writer.appendInline(text, TerminalStyle.Agent),
            writer.startInline("Agent", TerminalStyle.Agent) >> writer.appendInline(text, TerminalStyle.Agent) >> inlineOpen.set(true)
          )

        actions.evalMap {
          case AgentAction.Log(text) =>
            closeInline >> writer.printAbove("Agent", text, TerminalStyle.Agent)

          case AgentAction.AssistantDelta(text) =>
            appendAgentDelta(text)

          case AgentAction.AssistantEnd() =>
            closeInline

          case AgentAction.SystemMessage(text) =>
            closeInline >> writer.printSystemMessage(text, TerminalStyle.System)

          case AgentAction.ClearScreen() =>
            closeInline >> writer.clearScreen() >> writer.flush()

          case AgentAction.Exit() =>
            closeInline >> exitRequested.set(true)

          case AgentAction.StatusUpdate(text) =>
            status.updatePersistent(text)

          case AgentAction.ToolCallStart(name, args) =>
            val displayInput = if (name == "command_executor") {
              val commandPattern = """.*"command"\s*:\s*"([^"]*)".*""".r
              args match {
                case commandPattern(cmd) => cmd
                case _ => args
              }
            } else args

            val state = PanelState(panelConfig, Vector(s"Tool: $name", s"Cmd: $displayInput"))
            val rendered = summon[PanelRenderer[PanelState]].render(state)
            panelStateRef.set(Some(state)) >>
              status.updatePanel(rendered)

          case AgentAction.ToolCallOutput(text) =>
            panelStateRef.get.flatMap {
              case Some(state) =>
                val updated = state.copy(contentLines = state.contentLines :+ s"Output: $text")
                val rendered = summon[PanelRenderer[PanelState]].render(updated)
                panelStateRef.set(Some(updated)) >>
                  status.updatePanel(rendered)
              case None =>
                val state = PanelState(panelConfig, Vector(s"Output: $text"))
                val rendered = summon[PanelRenderer[PanelState]].render(state)
                panelStateRef.set(Some(state)) >>
                  status.updatePanel(rendered)
            }

          case AgentAction.ToolCallEnd() =>
            IO.unit

          case AgentAction.RequestChoice(prompt, options, allowCustom, onSelected) =>
            closeInline >> reader.readChoice(prompt, options, allowCustom).flatMap(choice => executeActions(onSelected(choice)))
        }.compile.drain.guarantee(closeInline >> status.clearPanel())
      }
    } yield ()
  }

  def loop: IO[Unit] = {
    val initMessage =
      writer.printLine("=== Tark Agent Lanterna TUI Initialized ===") >>
        writer.printLine("Type your prompt or /help. Press Ctrl+D or type /exit to close.\n") >>
        writer.flush()

    def promptLoop: IO[Unit] = {
      val prompt = "tark> "
      reader.readLine(prompt).flatMap {
        case InputResult.Exit =>
          IO.unit
        case InputResult.Cancelled =>
          writer.printSystemMessage("Action cancelled. Type /exit or press Ctrl+D to quit.", TerminalStyle.System) >> promptLoop
        case InputResult.Line(text) =>
          handleInput(text) >> exitRequested.get.ifM(IO.unit, promptLoop)
      }
    }

    initMessage >> promptLoop >> writer.printLine("\nExiting agent shell. Goodbye!") >> writer.flush()
  }
}

object LanternaFrontend {
  given ioSchedulable: Schedulable[IO] with {
    override def schedule(task: IO[Unit], delay: FiniteDuration, period: FiniteDuration): IO[Unit] = {
      def loop: IO[Unit] =
        task.handleErrorWith(error => IO.println(s"Spinner update failed: ${error.getMessage}")) >>
          IO.sleep(period) >>
          IO.cede >>
          loop

      IO.sleep(delay) >> loop
    }
  }

  given Animatable[SpinnerFrames] = Animatable.spinnerFrames

  def resource(
    screen: Screen,
    backend: AgentBackend[IO],
    completionRef: Ref[IO, List[String]]
  )(using Config): Resource[IO, LanternaFrontend] =
    Resource.eval {
      for {
        exitRequested <- Ref.of[IO, Boolean](false)
        stateRef <- Ref.of[IO, TuiState](TuiState())
      } yield {
        given AgentBackend[IO] = backend
        given TerminalStatus[IO] = LanternaTerminalStatus(screen, stateRef)
        val writer = LanternaTerminalWriter(screen, stateRef)
        val reader = LanternaTerminalReader(screen, stateRef, completionRef)
        val spinner = LanternaSpinner(stateRef, stateRef.get.flatMap(state => IO.blocking(LanternaTuiRenderer.render(screen, state))), 0.millis, 100.millis)
        LanternaFrontend(
          screen = screen,
          stateRef = stateRef,
          writer = writer,
          reader = reader,
          spinner = spinner,
          exitRequested = exitRequested
        )
      }
    }
}
