package com.tark.ports.inbound.ui

import cats.Monad
import cats.syntax.all.*
import com.tark.domain.context.Session
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.shared.ui.{ChatState, Layout}
import com.tark.ports.inbound.tool.InputProcessor

enum KeyboardAction {
  case Exit
  case Continue(state: ChatState, session: Session)
}

enum Key {
  case CtrlC
  case Enter
  case Backspace
  case CtrlU
  case CtrlD
  case CtrlB
  case CtrlF
  case ControlChar(ch: Int)
  case Printable(char: Char)
}

object Key {
  def fromInt(ch: Int): Key = ch match {
    case 3           => CtrlC
    case 10 | 13     => Enter
    case 127         => Backspace
    case 21          => CtrlU
    case 4           => CtrlD
    case 2           => CtrlB
    case 6           => CtrlF
    case c if c < 32 => ControlChar(c)
    case c           => Printable(c.toChar)
  }

  // Fallback given instance when no custom shortcuts are defined in lexical scope
  given emptyShortcuts[F[_]]: KeyboardShortcuts[F] = PartialFunction.empty
}

// Type representing the action mapped to a key press
type KeyAction[F[_]] = (ChatState, Session, ChatState => F[Unit], LlmClient[F]) => F[KeyboardAction]

// Type representing a collection of shortcuts
type KeyboardShortcuts[F[_]] = PartialFunction[Key, KeyAction[F]]

object Shortcuts {
  def default[F[_]: Monad](using inputProcessor: InputProcessor[F]): KeyboardShortcuts[F] = {
    case Key.CtrlC => (_, _, _, _) => 
      Monad[F].pure(KeyboardAction.Exit)

    case Key.Enter => (state, session, redraw, client) => 
      given LlmClient[F] = client
      val resetState = state.copy(scrollOffset = 0)
      inputProcessor.process(resetState.prompt, resetState, session, redraw).map {
        case None => KeyboardAction.Exit
        case Some((nextState, nextSession)) => KeyboardAction.Continue(nextState, nextSession)
      }

    case Key.Backspace => (state, session, _, _) =>
      val nextPrompt = if (state.prompt.isEmpty) "" else state.prompt.dropRight(1)
      val nextState = state.copy(prompt = nextPrompt)
      Monad[F].pure(KeyboardAction.Continue(nextState, session))

    case Key.CtrlU => (state, session, _, _) =>
      val wrappedHistory = Layout.wrapMessages(state.history, 80)
      val nextState = state.copy(scrollOffset = Math.min(wrappedHistory.length, state.scrollOffset + 10))
      Monad[F].pure(KeyboardAction.Continue(nextState, session))

    case Key.CtrlD => (state, session, _, _) =>
      val nextState = state.copy(scrollOffset = Math.max(0, state.scrollOffset - 10))
      Monad[F].pure(KeyboardAction.Continue(nextState, session))

    case Key.CtrlB => (state, session, _, _) =>
      val wrappedHistory = Layout.wrapMessages(state.history, 80)
      val nextState = state.copy(scrollOffset = Math.min(wrappedHistory.length, state.scrollOffset + 20))
      Monad[F].pure(KeyboardAction.Continue(nextState, session))

    case Key.CtrlF => (state, session, _, _) =>
      val nextState = state.copy(scrollOffset = Math.max(0, state.scrollOffset - 20))
      Monad[F].pure(KeyboardAction.Continue(nextState, session))
  }
}

trait KeyboardHandler[F[_]] {
  def handleKey(
    ch: Int,
    state: ChatState,
    session: Session,
    redraw: ChatState => F[Unit]
  )(using llmClient: LlmClient[F]): F[KeyboardAction]
}

object KeyboardHandler {
  given default[F[_]: Monad](using 
    inputProcessor: InputProcessor[F],
    customShortcuts: KeyboardShortcuts[F]
  ): KeyboardHandler[F] with {
    
    private val activeShortcuts = customShortcuts.orElse(Shortcuts.default[F])

    override def handleKey(
      ch: Int,
      state: ChatState,
      session: Session,
      redraw: ChatState => F[Unit]
    )(using llmClient: LlmClient[F]): F[KeyboardAction] = {
      val key = Key.fromInt(ch)

      if (activeShortcuts.isDefinedAt(key)) {
        activeShortcuts(key)(state, session, redraw, llmClient)
      } else {
        key match {
          case Key.ControlChar(_) =>
            // Ignore other control characters for input safety
            Monad[F].pure(KeyboardAction.Continue(state, session))
          case Key.Printable(char) =>
            // Append typed character
            val nextPrompt = state.prompt + char
            val nextState = state.copy(prompt = nextPrompt)
            Monad[F].pure(KeyboardAction.Continue(nextState, session))
          case _ =>
            Monad[F].pure(KeyboardAction.Continue(state, session))
        }
      }
    }
  }
}
