package com.tark.adapters.inbound.terminal.lanterna

import cats.effect.{IO, Ref}
import com.googlecode.lanterna.input.{KeyStroke, KeyType}
import com.googlecode.lanterna.screen.Screen
import com.tark.ui.{InputResult, TerminalReader}
import scala.concurrent.duration.*

final class LanternaTerminalReader(
  screen: Screen,
  stateRef: Ref[IO, TuiState],
  completionsRef: Ref[IO, List[String]]
) extends TerminalReader[IO] {

  private def redraw: IO[Unit] =
    stateRef.get.flatMap { state =>
      IO.blocking(LanternaTuiRenderer.render(screen, state))
    }

  private def updateInputState(input: String, cursor: Int): IO[Unit] =
    stateRef.update(_.copy(
      activeInput = input,
      cursorPosition = cursor
    )) >> redraw

  override def readLine(promptPrefix: String): IO[InputResult] = {
    val init = stateRef.update(_.copy(
      activePrompt = promptPrefix,
      activeInput = "",
      cursorPosition = 0
    )) >> redraw

    def loop(input: String, cursor: Int): IO[InputResult] = {
      IO.blocking(screen.readInput()).flatMap { key =>
        if (key == null) {
          IO.sleep(10.millis).flatMap(_ => loop(input, cursor))
        } else {
          key.getKeyType match {
            case KeyType.Character =>
              val c = key.getCharacter.toString
              val updatedInput = input.take(cursor) + c + input.drop(cursor)
              val updatedCursor = cursor + 1
              updateInputState(updatedInput, updatedCursor) >> loop(updatedInput, updatedCursor)

            case KeyType.Backspace =>
              if (cursor > 0) {
                val updatedInput = input.take(cursor - 1) + input.drop(cursor)
                val updatedCursor = cursor - 1
                updateInputState(updatedInput, updatedCursor) >> loop(updatedInput, updatedCursor)
              } else {
                loop(input, cursor)
              }

            case KeyType.ArrowLeft =>
              if (cursor > 0) {
                val updatedCursor = cursor - 1
                updateInputState(input, updatedCursor) >> loop(input, updatedCursor)
              } else {
                loop(input, cursor)
              }

            case KeyType.ArrowRight =>
              if (cursor < input.length) {
                val updatedCursor = cursor + 1
                updateInputState(input, updatedCursor) >> loop(input, updatedCursor)
              } else {
                loop(input, cursor)
              }

            case KeyType.Enter =>
              IO.pure(InputResult.Line(input))

            case KeyType.EOF =>
              IO.pure(InputResult.Exit)

            case KeyType.Escape =>
              IO.pure(InputResult.Cancelled)

            case KeyType.Tab =>
              completionsRef.get.flatMap { completions =>
                val wordStart = input.lastIndexOf(' ') + 1
                val prefix = input.substring(wordStart)
                if (prefix.nonEmpty) {
                  val matches = completions.filter(_.startsWith(prefix))
                  if (matches.nonEmpty) {
                    val completedWord = matches.head
                    val updatedInput = input.take(wordStart) + completedWord
                    val updatedCursor = updatedInput.length
                    updateInputState(updatedInput, updatedCursor) >> loop(updatedInput, updatedCursor)
                  } else {
                    loop(input, cursor)
                  }
                } else {
                  loop(input, cursor)
                }
              }

            case _ =>
              if (key.isCtrlDown && key.getCharacter == 'c') {
                IO.pure(InputResult.Cancelled)
              } else if (key.isCtrlDown && key.getCharacter == 'd') {
                IO.pure(InputResult.Exit)
              } else {
                loop(input, cursor)
              }
          }
        }
      }
    }

    init >> loop("", 0).guarantee {
      stateRef.update(_.copy(
        activePrompt = "",
        activeInput = "",
        cursorPosition = 0
      )) >> redraw
    }
  }

  override def readChoice(prompt: String, options: List[String], allowCustom: Boolean = false): IO[String] = {
    val menuOptions = if (allowCustom) options :+ "[Custom Option...]" else options

    def updateMenu(selectedIdx: Int): IO[Unit] = {
      val menuLines = Vector(s"? $prompt", "Use Arrow Keys/JK to navigate, Enter to select:") ++ menuOptions.zipWithIndex.map { case (opt, idx) =>
        if (idx == selectedIdx) s"  > $opt" else s"    $opt"
      }
      stateRef.update(_.copy(activePanelLines = menuLines)) >> redraw
    }

    def loop(selectedIdx: Int): IO[Int] = {
      IO.blocking(screen.readInput()).flatMap { key =>
        if (key == null) {
          IO.sleep(10.millis).flatMap(_ => loop(selectedIdx))
        } else {
          key.getKeyType match {
            case KeyType.ArrowUp =>
              val nextIdx = (selectedIdx - 1 + menuOptions.size) % menuOptions.size
              updateMenu(nextIdx) >> loop(nextIdx)

            case KeyType.ArrowDown =>
              val nextIdx = (selectedIdx + 1) % menuOptions.size
              updateMenu(nextIdx) >> loop(nextIdx)

            case KeyType.Character =>
              val c = key.getCharacter
              if (c == 'k' || c == 'K') {
                val nextIdx = (selectedIdx - 1 + menuOptions.size) % menuOptions.size
                updateMenu(nextIdx) >> loop(nextIdx)
              } else if (c == 'j' || c == 'J') {
                val nextIdx = (selectedIdx + 1) % menuOptions.size
                updateMenu(nextIdx) >> loop(nextIdx)
              } else if (key.isCtrlDown && c == 'c') {
                IO.pure(-1)
              } else {
                loop(selectedIdx)
              }

            case KeyType.Enter =>
              IO.pure(selectedIdx)

            case KeyType.Escape =>
              IO.pure(-1)

            case _ =>
              loop(selectedIdx)
          }
        }
      }
    }

    updateMenu(0) >> loop(0).flatMap { selectedIdx =>
      stateRef.update(_.copy(activePanelLines = Vector.empty)) >> redraw >> {
        if (selectedIdx == -1) {
          IO.pure("")
        } else {
          val selectedValue = menuOptions(selectedIdx)
          if (allowCustom && selectedValue == "[Custom Option...]") {
            readLine(s"custom $prompt: ").flatMap {
              case InputResult.Line(text) => IO.pure(text.trim)
              case _ => IO.pure("")
            }
          } else {
            IO.pure(selectedValue)
          }
        }
      }
    }
  }
}
