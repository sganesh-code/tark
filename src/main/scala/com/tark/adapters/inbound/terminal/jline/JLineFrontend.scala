package com.tark.adapters.inbound.terminal.jline

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tark.ports.AgentBackend
import com.tark.ui.*
import fs2.Stream
import org.jline.reader.impl.history.DefaultHistory
import org.jline.reader.{Candidate, Completer, EndOfFileException, Highlighter, LineReader, LineReaderBuilder, ParsedLine, UserInterruptException}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.{AttributedString, AttributedStringBuilder, AttributedStyle, Status}

import java.util.Collections
import scala.concurrent.duration.*

private[jline] object JLineChoiceMenu {
  enum Key:
    case Up, Down, Enter, Escape
    case Other(code: Int)

  def select(terminal: Terminal, prompt: String, options: List[String], allowCustom: Boolean): Int = {
    val originalAttributes = terminal.enterRawMode()
    val reader = terminal.reader()
    val menuOptions = if allowCustom then options :+ "[Custom Option...]" else options
    var selectedIndex = 0
    var selecting = true

    def drawMenu(): Unit = {
      val writer = terminal.writer()
      writer.println(s"\u001b[32m?\u001b[0m $prompt (Use arrow/jk keys, Enter to select):")
      menuOptions.zipWithIndex.foreach { case (option, idx) =>
        if idx == selectedIndex then writer.println(s"  \u001b[36m> $option\u001b[0m")
        else writer.println(s"    $option")
      }
      terminal.flush()
    }

    def clearMenu(): Unit = {
      val writer = terminal.writer()
      writer.print(s"\u001b[${menuOptions.size + 1}A\r")
      writer.print("\u001b[0J")
      terminal.flush()
    }

    def readKey(): Key = {
      val c = reader.read()
      if c == 27 then {
        val c2 = reader.read(10)
        if c2 == 91 then
          reader.read(10) match {
            case 65 => Key.Up
            case 66 => Key.Down
            case _  => Key.Other(c)
          }
        else Key.Escape
      } else if c == 10 || c == 13 then Key.Enter
      else if c == 'k' || c == 'K' then Key.Up
      else if c == 'j' || c == 'J' then Key.Down
      else if c == 3 || c == 4 then Key.Escape
      else Key.Other(c)
    }

    try {
      drawMenu()
      while selecting do
        readKey() match {
          case Key.Up =>
            clearMenu()
            selectedIndex = (selectedIndex - 1 + menuOptions.size) % menuOptions.size
            drawMenu()
          case Key.Down =>
            clearMenu()
            selectedIndex = (selectedIndex + 1) % menuOptions.size
            drawMenu()
          case Key.Enter =>
            selecting = false
          case Key.Escape =>
            selectedIndex = -1
            selecting = false
          case Key.Other(_) =>
            ()
        }
    } finally {
      terminal.setAttributes(originalAttributes)
      terminal.writer().print("\r")
      terminal.flush()
    }

    selectedIndex
  }
}

final class JLineTerminalReader(lineReader: LineReader) extends TerminalReader[IO]:
  override def readLine(promptPrefix: String): IO[InputResult] =
    IO.blocking {
      try {
        val line = lineReader.readLine(promptPrefix)
        if line == null then InputResult.Exit else InputResult.Line(line)
      } catch {
        case _: UserInterruptException => InputResult.Cancelled
        case _: EndOfFileException     => InputResult.Exit
      }
    }

  override def readChoice(prompt: String, options: List[String], allowCustom: Boolean): IO[String] =
    IO.blocking {
      val terminal = lineReader.getTerminal
      val menuOptions = if allowCustom then options :+ "[Custom Option...]" else options
      val selectedIndex = JLineChoiceMenu.select(terminal, prompt, options, allowCustom)

      if selectedIndex == -1 then ""
      else {
        val selectedValue = menuOptions(selectedIndex)
        if allowCustom && selectedValue == "[Custom Option...]" then
          Option(lineReader.readLine(s"\u001b[32mcustom $prompt:\u001b[0m ")).map(_.trim).getOrElse("")
        else selectedValue
      }
    }

final class JLineTerminalWriter(terminal: Terminal, lineReader: LineReader) extends TerminalWriter[IO]:
  override def printAbove(sender: String, message: String, style: TerminalStyle): IO[Unit] =
    IO.blocking {
      val styled = new AttributedStringBuilder()
        .append(s"[$sender] ", AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold())
        .append(message, JLineTerminalWriter.toJLineStyle(style))
        .toAttributedString
      lineReader.printAbove(styled)
    }

  override def startInline(sender: String, style: TerminalStyle): IO[Unit] =
    IO.blocking {
      val styled = new AttributedStringBuilder()
        .append(s"[$sender] ", AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold())
        .toAttributedString
      terminal.writer().print(styled.toAnsi(terminal))
      terminal.flush()
    }

  override def appendInline(message: String, style: TerminalStyle): IO[Unit] =
    IO.blocking {
      val styled = new AttributedStringBuilder()
        .append(message, JLineTerminalWriter.toJLineStyle(style))
        .toAttributedString
      terminal.writer().print(styled.toAnsi(terminal))
      terminal.flush()
    }

  override def finishInline(): IO[Unit] =
    IO.blocking {
      terminal.writer().println()
      terminal.flush()
    }

  override def printSystemMessage(message: String, style: TerminalStyle): IO[Unit] =
    IO.blocking {
      val styled = new AttributedStringBuilder()
        .append("[System] ", AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
        .append(message, JLineTerminalWriter.toJLineStyle(style))
        .toAttributedString
      lineReader.printAbove(styled)
    }

  override def printLine(message: String): IO[Unit] =
    IO.blocking(terminal.writer().println(message))

  override def clearScreen(): IO[Unit] =
    IO.blocking(terminal.puts(Capability.clear_screen))

  override def flush(): IO[Unit] =
    IO.blocking(terminal.flush())

object JLineTerminalWriter:
  def toJLineStyle(style: TerminalStyle): AttributedStyle = {
    val base = style.foreground match {
      case TerminalColor.Default => AttributedStyle.DEFAULT
      case TerminalColor.Black   => AttributedStyle.DEFAULT.foreground(AttributedStyle.BLACK)
      case TerminalColor.Red     => AttributedStyle.DEFAULT.foreground(AttributedStyle.RED)
      case TerminalColor.Green   => AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN)
      case TerminalColor.Yellow  => AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)
      case TerminalColor.Blue    => AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE)
      case TerminalColor.Magenta => AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA)
      case TerminalColor.Cyan    => AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN)
      case TerminalColor.White   => AttributedStyle.DEFAULT.foreground(AttributedStyle.WHITE)
    }
    val bolded = if style.bold then base.bold() else base
    if style.italic then bolded.italic() else bolded
  }

final class JLineTerminalStatus(terminal: Terminal) extends TerminalStatus[IO]:
  private val status: Option[Status] = Option(Status.getStatus(terminal))
  @volatile private var activeSpinner: String = ""
  @volatile private var persistentStatus: String = ""

  private def redraw(): Unit = {
    val parts = List(
      Option(activeSpinner).filter(_.nonEmpty),
      Option(persistentStatus).filter(_.nonEmpty)
    ).flatten
    status.foreach { s =>
      if (parts.isEmpty) {
        s.update(Collections.emptyList())
      } else {
        val combined = parts.mkString(" | ")
        s.update(Collections.singletonList(AttributedString.fromAnsi(s"\u001b[33m$combined\u001b[0m")))
      }
    }
  }

  override def update(content: String): IO[Unit] =
    IO.blocking {
      activeSpinner = content
      redraw()
    }

  override def clear(): IO[Unit] =
    IO.blocking {
      activeSpinner = ""
      redraw()
    }

  override def updatePersistent(content: String): IO[Unit] =
    IO.blocking {
      persistentStatus = content
      redraw()
    }

final class SimpleSpinner(delay: FiniteDuration, period: FiniteDuration) extends Spinner[IO, SpinnerFrames]:
  override def create(frame: SpinnerFrames, message: String)(using
    scheduler: Schedulable[IO],
    animatable: Animatable[SpinnerFrames],
    status: TerminalStatus[IO]
  ): Resource[IO, Unit] = {
    @volatile var frameIdx = 0
    val task =
      IO.defer {
        val currentFrame = animatable.frame(frame, frameIdx)
        status.update(s"$currentFrame $message") >> IO.delay {
          frameIdx = animatable.nextIdx(frame, frameIdx)
        }
      }
    Resource.make(scheduler.schedule(task, delay, period).start)(fiber => fiber.cancel >> status.clear()).void
  }

final class JLineCompleter(completionRef: Ref[IO, List[String]]) extends Completer:
  override def complete(reader: LineReader, line: ParsedLine, candidates: java.util.List[Candidate]): Unit = {
    val words = completionRef.get.unsafeRunSync()
    val buffer = line.word()
    words.filter(_.startsWith(buffer)).foreach(word => candidates.add(Candidate(word)))
  }

final class AgentCommandHighlighter extends Highlighter:
  override def highlight(reader: LineReader, buffer: String): AttributedString = {
    val builder = AttributedStringBuilder()
    if buffer.startsWith("/") then {
      val firstSpace = buffer.indexOf(' ')
      val (cmd, args) = if firstSpace == -1 then (buffer, "") else buffer.splitAt(firstSpace)
      builder.append(cmd, AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
      builder.append(args, AttributedStyle.DEFAULT)
    } else {
      builder.append(buffer, AttributedStyle.DEFAULT)
    }
    builder.toAttributedString
  }

  override def setErrorPattern(errorPattern: java.util.regex.Pattern): Unit = ()
  override def setErrorIndex(errorIndex: Int): Unit = ()

final class JLineFrontend(
  writer: TerminalWriter[IO],
  reader: TerminalReader[IO],
  spinner: Spinner[IO, SpinnerFrames],
  exitRequested: Ref[IO, Boolean]
)(using
  backend: AgentBackend[IO],
  scheduler: Schedulable[IO],
  animatable: Animatable[SpinnerFrames],
  status: TerminalStatus[IO]
) extends AgentFrontend[IO] {

  private val frames = SpinnerFrames(Vector("|", "/", "-", "\\"))

  override def handleInput(input: String)(using backend: AgentBackend[IO]): IO[Unit] = {
    val cleanInput = input.trim
    if cleanInput.isEmpty then IO.unit
    else
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

  private def executeActions(actions: Stream[IO, AgentAction[IO]]): IO[Unit] =
    Ref.of[IO, Boolean](false).flatMap { inlineOpen =>
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

        case AgentAction.RequestChoice(prompt, options, allowCustom, onSelected) =>
          closeInline >> reader.readChoice(prompt, options, allowCustom).flatMap(choice => executeActions(onSelected(choice)))
      }.compile.drain.guarantee(closeInline)
    }

  def loop: IO[Unit] = {
    val initMessage =
      writer.printLine("\u001b[34;1m=== Tark Agent TUI Initialized ===\u001b[0m") >>
        writer.printLine("Type your prompt or /help. Press Ctrl+D or type /exit to close.\n") >>
        writer.flush()

    def promptLoop: IO[Unit] =
      reader.readLine("\u001b[32mtark>\u001b[0m ").flatMap {
        case InputResult.Exit =>
          IO.unit
        case InputResult.Cancelled =>
          writer.printSystemMessage("Action cancelled. Type /exit or press Ctrl+D to quit.", TerminalStyle.System) >> promptLoop
        case InputResult.Line(text) =>
          handleInput(text) >> exitRequested.get.ifM(IO.unit, promptLoop)
      }

    initMessage >> promptLoop >> writer.printLine("\nExiting agent shell. Goodbye!") >> writer.flush()
  }
}

object JLineFrontend:
  given ioSchedulable: Schedulable[IO] with
    override def schedule(task: IO[Unit], delay: FiniteDuration, period: FiniteDuration): IO[Unit] = {
      def loop: IO[Unit] =
        task.handleErrorWith(error => IO.println(s"Spinner update failed: ${error.getMessage}")) >>
          IO.sleep(period) >>
          loop

      IO.sleep(delay) >> loop
    }

  given Animatable[SpinnerFrames] = Animatable.spinnerFrames

  def terminalAndReader(completionRef: Ref[IO, List[String]]): Resource[IO, (Terminal, LineReader)] =
    Resource.make {
      IO.blocking {
        val terminal = TerminalBuilder.builder()
          .system(true)
          .signalHandler(Terminal.SignalHandler.SIG_IGN)
          .build()

        val lineReader = LineReaderBuilder.builder()
          .terminal(terminal)
          .history(DefaultHistory())
          .completer(JLineCompleter(completionRef))
          .highlighter(AgentCommandHighlighter())
          .build()

        val autosuggestions = org.jline.widget.AutosuggestionWidgets(lineReader)
        autosuggestions.enable()

        (terminal, lineReader)
      }
    } { case (terminal, _) => IO.blocking(terminal.close()) }

  def resource(
    terminal: Terminal,
    lineReader: LineReader,
    backend: AgentBackend[IO]
  ): Resource[IO, JLineFrontend] =
    Resource.eval {
      for {
        exitRequested <- Ref.of[IO, Boolean](false)
      } yield {
        given AgentBackend[IO] = backend
        given TerminalStatus[IO] = JLineTerminalStatus(terminal)
        JLineFrontend(
          writer = JLineTerminalWriter(terminal, lineReader),
          reader = JLineTerminalReader(lineReader),
          spinner = SimpleSpinner(0.millis, 100.millis),
          exitRequested = exitRequested
        )
      }
    }
