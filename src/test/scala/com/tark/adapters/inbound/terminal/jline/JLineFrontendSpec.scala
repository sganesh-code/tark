package com.tark.adapters.inbound.terminal.jline

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.context.Session
import com.tark.domain.tool.ToolCall
import com.tark.domain.ui.Screen
import com.tark.ports.inbound.ui.{KeyboardAction, KeyboardHandler}
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, Prompt}
import com.tark.ports.outbound.ui.ScreenWriter
import com.tark.ports.shared.ui.{ChatState, Layout}
import munit.FunSuite
import org.jline.terminal.TerminalBuilder

class JLineFrontendSpec extends FunSuite {
  test("JLineFrontend: successfully executes redraw on dumb terminal") {
    val terminal = TerminalBuilder.builder().dumb(true).build()

    given layout: Layout[ChatState] with {
      override def render(state: ChatState, width: Int, height: Int): Screen = Screen(10, 5)
    }

    given screenWriter: ScreenWriter[Screen] with {
      override def write(screen: Screen, writer: java.io.PrintWriter): IO[Unit] = IO.unit
    }

    given keyboardHandler: KeyboardHandler[IO] with {
      override def handleKey(ch: Int, state: ChatState, session: Session, redraw: ChatState => IO[Unit])(using LlmClient[IO]): IO[KeyboardAction] =
        IO.pure(KeyboardAction.Exit)
    }

    given llmClient: LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.pure(LLMResponse("", List.empty))
    }

    val frontend = JLineFrontend(terminal)
    frontend.redraw(ChatState(Vector.empty, "abc")).unsafeRunSync()
  }
}
