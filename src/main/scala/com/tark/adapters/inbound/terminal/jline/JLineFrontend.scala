package com.tark.adapters.inbound.terminal.jline

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tark.ports.AgentBackend
import com.tark.ui.*
import com.tark.domain.Config
import fs2.Stream
import org.jline.reader.impl.history.DefaultHistory
import org.jline.reader.{Candidate, Completer, EndOfFileException, Highlighter, LineReader, LineReaderBuilder, ParsedLine, UserInterruptException}
import org.jline.terminal.{Terminal, TerminalBuilder}
import org.jline.utils.InfoCmp.Capability
import org.jline.utils.{AttributedString, AttributedStringBuilder, AttributedStyle, Status}

import java.util.Collections
import scala.concurrent.duration.*

import org.jline.prompt.{PrompterFactory, ListResult}

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
      val prompter = PrompterFactory.create(terminal)
      val builder = prompter.newBuilder()

      val listBuilder = builder.createListPrompt()
        .name("choice")
        .message(prompt)

      options.zipWithIndex.foreach { case (opt, idx) =>
        listBuilder.newItem(s"opt_$idx").text(opt)
      }
      if (allowCustom) {
        listBuilder.newItem("custom").text("[Custom Option...]")
      }
      listBuilder.addPrompt()

      val results = prompter.prompt(null, builder.build())
      val selectedId = Option(results.get("choice"))
        .map(_.asInstanceOf[ListResult].getSelectedId)
        .getOrElse("")

      if (selectedId == "custom") {
        val inputBuilder = prompter.newBuilder()
        inputBuilder.createInputPrompt()
          .name("custom_input")
          .message(s"custom $prompt:")
          .addPrompt()

        val inputResults = prompter.prompt(null, inputBuilder.build())
        Option(inputResults.get("custom_input"))
          .map(_.asInstanceOf[org.jline.prompt.InputResult].getInput.trim)
          .getOrElse("")
      } else {
        val idxOpt = selectedId.stripPrefix("opt_").toIntOption
        idxOpt.map(options).getOrElse("")
      }
    }

final class JLineTerminalWriter(val terminal: Terminal, lineReader: LineReader) extends TerminalWriter[IO]:
  def terminalWidth: Int = terminal.getWidth

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

  override def printPanel(panelText: String): IO[Unit] =
    IO.blocking {
      terminal.writer().println(panelText)
      terminal.flush()
    }

  override def printLine(message: String): IO[Unit] =
    IO.blocking(terminal.writer().println(message))

  override def clearScreen(): IO[Unit] =
    IO.blocking(terminal.puts(Capability.clear_screen))

  override def flush(): IO[Unit] =
    IO.blocking(terminal.flush())

object JLineTerminalWriter:
  private def toJLineStyle(style: TerminalStyle): AttributedStyle = {
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
  private val status: Option[Status] = {
    if (terminal.getType == Terminal.TYPE_DUMB || terminal.getType == Terminal.TYPE_DUMB_COLOR) None
    else {
      val s = Option(Status.getStatus(terminal))
      if (s.exists(_.toString.contains("supported=true"))) s else None
    }
  }
  @volatile private var activeSpinner: String = ""
  @volatile private var persistentStatus: String = ""
  @volatile private var activePanelLines: Vector[String] = Vector.empty

  private def redraw(): Unit = {
    status.foreach { s =>
      if (activeSpinner.isEmpty && persistentStatus.isEmpty && activePanelLines.isEmpty) {
        s.update(Collections.emptyList())
      } else {
        val lines = new java.util.ArrayList[AttributedString]()
        val width = if (terminal.getColumns > 0) terminal.getColumns else 80

        val leftBuilder = new AttributedStringBuilder()
        if (activeSpinner.nonEmpty) {
          leftBuilder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold())
            .append(activeSpinner)
        }
        if (activeSpinner.nonEmpty && persistentStatus.nonEmpty) {
          leftBuilder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK))
            .append(" | ")
        }
        if (persistentStatus.nonEmpty) {
          leftBuilder.style(AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
            .append(persistentStatus)
        }
        val leftStr = leftBuilder.toAttributedString

        val rightStr = new AttributedStringBuilder()
          .style(AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT | AttributedStyle.BLACK))
          .append("[Tark Agent TUI]")
          .toAttributedString

        val leftLen = activeSpinner.length + (if (activeSpinner.nonEmpty && persistentStatus.nonEmpty) 3 else 0) + persistentStatus.length
        val rightLen = 16
        val padding = Math.max(0, width - leftLen - rightLen)

        val combined = new AttributedStringBuilder()
          .append(leftStr)
          .append(" " * padding)
          .append(rightStr)
          .toAttributedString

        lines.add(combined)

        activePanelLines.foreach { line =>
          lines.add(AttributedString.fromAnsi(line))
        }
        s.update(lines)
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

  override def updatePanel(lines: Vector[String])(using F: cats.Applicative[IO]): IO[Unit] =
    IO.blocking {
      activePanelLines = lines
      redraw()
    }

  override def clearPanel()(using F: cats.Applicative[IO]): IO[Unit] =
    IO.blocking {
      activePanelLines = Vector.empty
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

import java.util.regex.Pattern

final class AgentCommandHighlighter extends Highlighter:
  private val COMMANDS = Pattern.compile("^/(help|clear|memory|run|exit)\\b")
  private val FLAGS    = Pattern.compile("(?<=\\s|^)--?[a-zA-Z0-9_-]+\\b")
  private val STRINGS  = Pattern.compile("\"[^\"]*\"|'[^']*'")
  private val NUMBERS  = Pattern.compile("\\b\\d+(\\.\\d+)?\\b")
  private val BRACKETS = Pattern.compile("[\\[\\](){}]")

  override def highlight(reader: LineReader, buffer: String): AttributedString = {
    val len = buffer.length
    val styles = Array.fill[AttributedStyle](len)(AttributedStyle.DEFAULT)

    def applyPattern(pattern: Pattern, style: AttributedStyle): Unit = {
      val matcher = pattern.matcher(buffer)
      while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        for (i <- start until end) {
          styles(i) = style
        }
      }
    }

    applyPattern(BRACKETS, AttributedStyle.BOLD.foreground(AttributedStyle.MAGENTA))
    applyPattern(NUMBERS, AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE))
    applyPattern(STRINGS, AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN))
    applyPattern(FLAGS, AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
    applyPattern(COMMANDS, AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW))

    val builder = new AttributedStringBuilder()
    for (i <- 0 until len) {
      builder.append(buffer.charAt(i).toString, styles(i))
    }
    builder.toAttributedString
  }

  override def setErrorPattern(errorPattern: java.util.regex.Pattern): Unit = ()
  override def setErrorIndex(errorIndex: Int): Unit = ()

final class JLineFrontend(
  writer: TerminalWriter[IO],
  reader: TerminalReader[IO],
  spinner: Spinner[IO, SpinnerFrames],
  exitRequested: Ref[IO, Boolean],
  activePanelLinesRef: Ref[IO, Vector[String]]
)(using
  backend: AgentBackend[IO],
  scheduler: Schedulable[IO],
  animatable: Animatable[SpinnerFrames],
  status: TerminalStatus[IO],
  config: Config = Config.default
) extends AgentFrontend[IO] {

  private val frames = SpinnerFrames(Vector("|", "/", "-", "\\"))

  override def handleInput(input: String)(using backend: AgentBackend[IO]): IO[Unit] = {
    val cleanInput = input.trim
    if cleanInput.isEmpty then IO.unit
    else
      val terminalOpt = writer match {
        case jWriter: JLineTerminalWriter => Some(jWriter.terminal)
        case _ => None
      }

      val execution = status.clearPanel() >>
        activePanelLinesRef.set(Vector.empty) >>
        backend.handleInput(cleanInput).evalMap { task =>
          task.description match {
            case Some(description) =>
              spinner.create(frames, description).use(_ => executeActions(task.action))
            case None =>
              executeActions(task.action)
          }
        }.compile.drain

      val finalExecution = if (cleanInput == "/exit") {
        execution.handleErrorWith { error =>
          writer.printSystemMessage(s"Exit warning (failed to summarize/persist): ${error.getMessage}", TerminalStyle.System)
        } >> exitRequested.set(true)
      } else {
        execution.handleErrorWith { error =>
          writer.printAbove("System", s"Error: ${error.getMessage}", TerminalStyle.Error)
        }
      }

      terminalOpt match {
        case Some(terminal) if terminal.getType != Terminal.TYPE_DUMB && terminal.getType != Terminal.TYPE_DUMB_COLOR =>
          cats.effect.Deferred[IO, Unit].flatMap { cancelSig =>
            val escListener: IO[Unit] = IO.blocking {
              val reader = terminal.reader()
              var cancelled = false
              val originalAttributes = terminal.enterRawMode()
              try {
                while (!cancelled && !Thread.interrupted()) {
                  val c = reader.peek(100) // 100ms non-blocking check
                  if (c == 27 || c == 3) { // Esc (27) or Ctrl+C (3)
                    reader.read() // consume matched key
                    cancelled = true
                  } else if (c > 0) {
                    reader.read() // consume other keys typed during execution to keep next prompt clean
                  } else if (c == -1) {
                    cancelled = true // EOF
                  } else if (c == -2) {
                    Thread.sleep(10) // Small sleep to prevent CPU spinning on quick timeouts
                  }
                }
              } finally {
                terminal.setAttributes(originalAttributes)
              }
            }

            for {
              escFiber <- (escListener >> cancelSig.complete(()).void).start
              res <- IO.race(finalExecution, cancelSig.get).flatMap {
                case Left(_) =>
                  // Normal execution completed. Block and wait for background fiber to cleanly restore terminal attributes.
                  escFiber.cancel
                case Right(_) =>
                  // User pressed Esc or Ctrl+C. Cancel execution and display notice.
                  val printNotice = if (cleanInput == "/exit") exitRequested.set(true) else IO.unit
                  writer.printSystemMessage("Processing skipped by user (Esc pressed).", TerminalStyle.System) >> printNotice
              }
            } yield res
          }

        case _ =>
          finalExecution
      }
  }

  private def executeActions(actions: Stream[IO, AgentAction[IO]]): IO[Unit] = {
    val cols = terminalWidth
    val width = if (cols > 0) cols - 1 else config.panelWidth

    Ref.of[IO, Int](0).flatMap { linesPrintedRef =>
      Ref.of[IO, Boolean](false).flatMap { inlineOpen =>
        def closeInline: IO[Unit] =
          inlineOpen.get.ifM(writer.finishInline() >> inlineOpen.set(false), IO.unit)

        def appendAgentDelta(text: String): IO[Unit] =
          inlineOpen.get.ifM(
            writer.appendInline(text, TerminalStyle.Agent),
            writer.startInline("Agent", TerminalStyle.Agent) >> writer.appendInline(text, TerminalStyle.Agent) >> inlineOpen.set(true)
          )

        val MaxOutputLines = 15

        def printBorderedLine(text: String): IO[Unit] = {
          val maxContentWidth = width - 4
          if (maxContentWidth <= 0) writer.printLine(text)
          else {
            val lines = text.split("\n", -1).toList
            val rawSlices = lines.flatMap { line =>
              if (line.isEmpty) List("│ " + " " * maxContentWidth + " │")
              else {
                line.grouped(maxContentWidth).map { chunk =>
                  "│ " + chunk.padTo(maxContentWidth, ' ').take(maxContentWidth) + " │"
                }.toList
              }
            }

            rawSlices.traverse { formattedLine =>
              linesPrintedRef.get.flatMap { current =>
                if (current < MaxOutputLines) {
                  writer.printLine(formattedLine) >> linesPrintedRef.update(_ + 1)
                } else {
                  linesPrintedRef.update(_ + 1)
                }
              }
            }.void
          }
        }

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
            val displayInput = if name == "command_executor" then
              val commandPattern = """.*"command"\s*:\s*"([^"]*)".*""".r
              args match {
                case commandPattern(cmd) => cmd
                case _ => args
              }
            else args

            val maxContentWidth = width - 4
            val topBorder = "┌" + s"─ Tool: $name ".padTo(width - 2, '─').take(width - 2) + "┐"
            val cmdLine = "│ " + s"Cmd: $displayInput".padTo(maxContentWidth, ' ').take(maxContentWidth) + " │"
            val midBorder = "├" + "─ Output ".padTo(width - 2, '─').take(width - 2) + "┤"

            closeInline >>
              linesPrintedRef.set(0) >>
              writer.printLine(topBorder) >>
              writer.printLine(cmdLine) >>
              writer.printLine(midBorder)

          case AgentAction.ToolCallOutput(text) =>
            printBorderedLine(text)

          case AgentAction.ToolCallEnd() =>
            linesPrintedRef.get.flatMap { finalCount =>
              val maxContentWidth = width - 4
              val printTruncationNotice = if (finalCount > MaxOutputLines) {
                val truncatedCount = finalCount - MaxOutputLines
                val truncLine = "│ " + s"... [TRUNCATED $truncatedCount LINES]".padTo(maxContentWidth, ' ').take(maxContentWidth) + " │"
                writer.printLine(truncLine)
              } else IO.unit

              val bottomBorder = "└" + ("─" * (width - 2)) + "┘"
              printTruncationNotice >> writer.printLine(bottomBorder) >> linesPrintedRef.set(0)
            }

          case AgentAction.RequestChoice(prompt, options, allowCustom, onSelected) =>
            closeInline >> reader.readChoice(prompt, options, allowCustom).flatMap(choice => executeActions(onSelected(choice)))
        }.compile.drain.guarantee(closeInline >> status.clearPanel())
      }
    }
  }

  private def terminalWidth: Int =
    writer match {
      case jWriter: JLineTerminalWriter =>
        // Safely extract width if possible, or fallback
        // We can get terminal width directly or via lineReader
        jWriter.terminalWidth
      case _ =>
        config.panelWidth
    }

  def loop: IO[Unit] = {
    val initMessage =
      writer.printLine("\u001b[34;1m=== Tark Agent TUI Initialized ===\u001b[0m") >>
        writer.printLine("Type your prompt or /help. Press Ctrl+D or type /exit to close.") >>
        writer.printLine("\u001b[90m* Hint: Press ESC or Ctrl+C at any time during active processing to interrupt and return to the prompt.\u001b[0m\n") >>
        writer.flush()

    def promptLoop: IO[Unit] = {
      val prompt = "\u001b[32mtark>\u001b[0m "
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

object JLineFrontend:
  given ioSchedulable: Schedulable[IO] with
    override def schedule(task: IO[Unit], delay: FiniteDuration, period: FiniteDuration): IO[Unit] = {
      def loop: IO[Unit] =
        task.handleErrorWith(error => IO.println(s"Spinner update failed: ${error.getMessage}")) >>
          IO.sleep(period) >>
          IO.cede >>
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

        val emptyArgs = new java.util.ArrayList[org.jline.console.ArgDesc]()
        val emptyOpts = new java.util.HashMap[String, java.util.List[AttributedString]]()

        val tailTips = new java.util.HashMap[String, org.jline.console.CmdDesc]()
        tailTips.put("/help", new org.jline.console.CmdDesc(java.util.Arrays.asList(AttributedString.fromAnsi("Display help information")), emptyArgs, emptyOpts))
        tailTips.put("/clear", new org.jline.console.CmdDesc(java.util.Arrays.asList(AttributedString.fromAnsi("Summarize session history and clear terminal screen")), emptyArgs, emptyOpts))
        tailTips.put("/memory", new org.jline.console.CmdDesc(java.util.Arrays.asList(AttributedString.fromAnsi("Show the current multi-layered episodic/working memory state")), emptyArgs, emptyOpts))
        tailTips.put("/exit", new org.jline.console.CmdDesc(java.util.Arrays.asList(AttributedString.fromAnsi("Summarize, persist session, and exit the shell")), emptyArgs, emptyOpts))

        val runOpts = new java.util.HashMap[String, java.util.List[AttributedString]]()
        tailTips.put("/run", new org.jline.console.CmdDesc(
          java.util.Arrays.asList(AttributedString.fromAnsi("Execute a terminal/shell command inside the agent's sandbox")),
          java.util.Arrays.asList(new org.jline.console.ArgDesc("<command>")),
          runOpts
        ))

        val tailtipWidgets = new org.jline.widget.TailTipWidgets(lineReader, tailTips, 5, org.jline.widget.TailTipWidgets.TipType.COMPLETER)
        tailtipWidgets.enable()

        (terminal, lineReader)
      }
    } { case (terminal, _) => IO.blocking(terminal.close()) }

  def resource(
    terminal: Terminal,
    lineReader: LineReader,
    backend: AgentBackend[IO]
  )(using Config): Resource[IO, JLineFrontend] =
    Resource.eval {
      for {
        exitRequested <- Ref.of[IO, Boolean](false)
        activePanelLinesRef <- Ref.of[IO, Vector[String]](Vector.empty)
      } yield {
        given AgentBackend[IO] = backend
        given TerminalStatus[IO] = JLineTerminalStatus(terminal)
        JLineFrontend(
          writer = JLineTerminalWriter(terminal, lineReader),
          reader = JLineTerminalReader(lineReader),
          spinner = SimpleSpinner(0.millis, 100.millis),
          exitRequested = exitRequested,
          activePanelLinesRef = activePanelLinesRef
        )
      }
    }
