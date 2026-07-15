package com.tark.adapters.inbound.terminal.jline

import cats.effect.IO
import com.tark.domain.context.Session
import com.tark.domain.ui.Screen
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.inbound.ui.{KeyboardAction, KeyboardHandler}
import com.tark.ports.outbound.ui.{Frontend, ScreenWriter}
import com.tark.ports.shared.ui.{ChatState, Layout}
import org.jline.terminal.Terminal

class JLineFrontend(terminal: Terminal)(using
  layout: Layout[ChatState],
  screenWriter: ScreenWriter[Screen],
  keyboardHandler: KeyboardHandler[IO],
  llmClient: LlmClient[IO]
) extends Frontend[IO] {

  private val reader = terminal.reader()
  private val writer = terminal.writer()
  private var lastScreen: Option[Screen] = None

  override def redraw(state: ChatState): IO[Unit] = {
    val width = terminal.getWidth
    val height = terminal.getHeight
    val virtualHeight = Math.max(5, height - 1)
    val screen = layout.render(state, width, virtualHeight)

    val renderIO = lastScreen match {
      case Some(ls) if ls.width == width && ls.height == virtualHeight =>
        screenWriter.writeDelta(screen, ls, writer)
      case _ =>
        screenWriter.write(screen, writer)
    }

    renderIO.flatMap { _ =>
      IO {
        val screenCopy = Screen(width, virtualHeight)
        for {
          y <- 0 until virtualHeight
          x <- 0 until width
        } {
          screenCopy.put(x, y, screen.cell(x, y))
        }
        lastScreen = Some(screenCopy)

        val maxPromptWidth = Math.max(1, width - 6)
        val cursorCol = 5 + Math.min(state.prompt.length, maxPromptWidth)
        writer.print(s"\u001b[${virtualHeight - 1};${cursorCol}H")
        writer.flush()
      }
    }
  }

  override def loop(state: ChatState, session: Session): IO[Unit] = for {
    _ <- redraw(state)
    ch <- IO.blocking(reader.read())
    action <- keyboardHandler.handleKey(ch, state, session, redraw)
    _ <- action match {
      case KeyboardAction.Exit => IO.unit
      case KeyboardAction.Continue(nextState, nextSession) => loop(nextState, nextSession)
    }
  } yield ()
}
