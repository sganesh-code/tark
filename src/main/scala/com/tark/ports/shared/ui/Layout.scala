package com.tark.ports.shared.ui

import com.tark.domain.ui.{Cell, Color, Screen, Style}
import com.tark.ports.shared.ui.Formattable

trait Colorable[A] {
  def color(value: A): Color
}

object Colorable {
  def apply[A](using ev: Colorable[A]): Colorable[A] = ev
}

sealed trait Message {
  def cells: Vector[Cell]
  def text: String = cells.map(_.glyph).mkString
  def fg: Color = cells.headOption.map(_.fg).getOrElse(Color.Default)
  def bg: Color = cells.headOption.map(_.bg).getOrElse(Color.Default)
  def styles: Set[Style] = cells.headOption.map(_.styles).getOrElse(Set.empty)
}

object Message {
  case class User(cells: Vector[Cell]) extends Message
  object User {
    def apply(text: String): User =
      User(text.map(c => Cell(c.toString, Color.BrightGreen)).toVector)
    def apply(text: String, fg: Color, bg: Color = Color.Default, styles: Set[Style] = Set.empty): User =
      User(text.map(c => Cell(c.toString, fg, bg, styles)).toVector)
  }

  case class AI(cells: Vector[Cell]) extends Message
  object AI {
    def apply(text: String): AI =
      AI(text.map(c => Cell(c.toString, Color.BrightBlue)).toVector)
    def apply(text: String, fg: Color, bg: Color = Color.Default, styles: Set[Style] = Set.empty): AI =
      AI(text.map(c => Cell(c.toString, fg, bg, styles)).toVector)
  }

  case class System(cells: Vector[Cell]) extends Message
  object System {
    def apply(text: String): System =
      System(text.map(c => Cell(c.toString, Color.BrightRed)).toVector)
    def apply(text: String, fg: Color, bg: Color = Color.Default, styles: Set[Style] = Set.empty): System =
      System(text.map(c => Cell(c.toString, fg, bg, styles)).toVector)
  }

  given Colorable[Message] with {
    override def color(value: Message): Color = value.fg
  }

  given Formattable[Message] with {
    override def format(msg: Message, fg: Option[Color], bg: Option[Color], styles: Option[Set[Style]]): Message = {
      val cellFormatter = summon[Formattable[Cell]]
      val formattedCells = msg.cells.map(c => cellFormatter.format(c, fg, bg, styles))
      msg match {
        case u: User   => u.copy(cells = formattedCells)
        case a: AI     => a.copy(cells = formattedCells)
        case s: System => s.copy(cells = formattedCells)
      }
    }
  }
}

case class ChatState(
                      history: Vector[Message],
                      prompt: String,
                      scrollOffset: Int = 0,
                      currentThought: Option[String] = None
                    )

object ChatState {
  /**
   * Processes the current prompt when the user presses Enter.
   * Returns Some(nextState) to continue, or None to exit the application.
   */
  def transition(state: ChatState): Option[ChatState] = {
    val trimmed = state.prompt.trim
    if (trimmed == "/exit") {
      None
    } else if (trimmed == "/clear") {
      Some(state.copy(history = Vector.empty, prompt = "", scrollOffset = 0))
    } else if (trimmed == "/help") {
      val helpMsg = "Available commands: /help, /clear, /memory, /run <command>, /exit"
      Some(
        state.copy(
          history = (state.history :+ Message.User(state.prompt)) :+ Message.System(helpMsg),
          prompt = "",
          scrollOffset = 0
        )
      )
    } else if (trimmed.startsWith("/")) {
      Some(
        state.copy(
          history = (state.history :+ Message.User(state.prompt)) :+ Message.System(s"Unknown command: $trimmed"),
          prompt = "",
          scrollOffset = 0
        )
      )
    } else {
      Some(
        ChatState(
          state.history :+ Message.User(state.prompt), "", scrollOffset = 0
        )
      )
    }
  }
}

case class BorderStyle(
  topLeft: String,
  topRight: String,
  bottomLeft: String,
  bottomRight: String,
  horizontal: String,
  vertical: String,
  dividerLeft: String,
  dividerRight: String
)

object BorderStyle {
  val UnicodeRounded: BorderStyle = BorderStyle("╭", "╮", "╰", "╯", "─", "│", "├", "┤")
  val UnicodeSquare: BorderStyle = BorderStyle("┌", "┐", "└", "┘", "─", "│", "├", "┤")
  val Ascii: BorderStyle = BorderStyle("+", "+", "+", "+", "-", "|", "+", "+")
}

case class LayoutConfig(
  appTitle: String = "Tark CLI",
  promptPrefix: String = "❯ ",
  thoughtPrefix: String = "(...)",
  borderStyle: BorderStyle = BorderStyle.UnicodeRounded
)

object LayoutConfig {
  val default: LayoutConfig = LayoutConfig()
}

trait Layout[S] {
  def render(state: S, width: Int, height: Int): Screen
}

object Layout {
  def wrapText(text: String, maxWidth: Int): List[String] = {
    if (maxWidth <= 0) List(text)
    else {
      text.split("\n", -1).toList.flatMap { line =>
        if (line.length <= maxWidth) List(line)
        else {
          val words = line.split(" ")
          val (lines, currentLine) = words.foldLeft((List.empty[String], "")) { case ((acc, curr), word) =>
            if (curr.isEmpty) {
              if (word.length > maxWidth) {
                (acc :+ word.take(maxWidth), word.drop(maxWidth))
              } else {
                (acc, word)
              }
            } else {
              val potentialLine = curr + " " + word
              if (potentialLine.length <= maxWidth) {
                (acc, potentialLine)
              } else {
                if (word.length > maxWidth) {
                  (acc :+ curr :+ word.take(maxWidth), word.drop(maxWidth))
                } else {
                  (acc :+ curr, word)
                }
              }
            }
          }
          if (currentLine.isEmpty) lines else lines :+ currentLine
        }
      }
    }
  }

  def wrapMessages(history: Vector[Message], maxLineWidth: Int): Vector[Message] = {
    history.flatMap { msg =>
      val textLines = wrapText(msg.text, maxLineWidth)
      textLines.map { line =>
        msg match {
          case _: Message.User   => Message.User(line, msg.fg, msg.bg, msg.styles)
          case _: Message.AI     => Message.AI(line, msg.fg, msg.bg, msg.styles)
          case _: Message.System => Message.System(line, msg.fg, msg.bg, msg.styles)
        }
      }
    }
  }

  given (using config: LayoutConfig = LayoutConfig.default): Layout[ChatState] with {
    override def render(state: ChatState, width: Int, height: Int): Screen = {
      val screen = Screen(width, height)

      // 1. Render Header
      renderHeader(screen, config, width)

      val thoughtHeight = 3
      // 2. Render History
      val conversationHeight = Math.max(1, height - (thoughtHeight + 5))
      renderHistory(screen, config, state, width, conversationHeight)

      // 3. Render Thought Rows immediately above the bottom border
      renderThought(screen, config, state.currentThought, width, height - (thoughtHeight + 3), thoughtHeight)

      // 4. Render Bottom Border Divider
      screen.put(0, height - 3, config.borderStyle.dividerLeft + config.borderStyle.horizontal * (width - 2) + config.borderStyle.dividerRight)

      // 5. Render Prompt Row
      renderPrompt(screen, config, state.prompt, width, height - 2)

      // 6. Render Footer Row (closing prompt box)
      renderFooter(screen, config, width, height - 1)

      screen
    }

    private def renderHeader(screen: Screen, config: LayoutConfig, width: Int): Unit = {
      val border = config.borderStyle
      screen.put(0, 0, border.topLeft + border.horizontal * (width - 2) + border.topRight)
      
      val titleStr = border.vertical + s" ${config.appTitle}"
      val remaining = Math.max(0, width - titleStr.length - 1)
      screen.put(0, 1, titleStr + " " * remaining + border.vertical)
    }

    private def renderHistory(screen: Screen, config: LayoutConfig, state: ChatState, width: Int, conversationHeight: Int): Unit = {
      val border = config.borderStyle
      val maxLineWidth = Math.max(1, width - 4)
      val wrappedHistory = wrapMessages(state.history, maxLineWidth)

      val maxScrollOffset = Math.max(0, wrappedHistory.length - conversationHeight)
      val clampedOffset = Math.min(maxScrollOffset, Math.max(0, state.scrollOffset))

      val visibleHistory = if (clampedOffset > 0) {
        wrappedHistory.dropRight(clampedOffset)
      } else {
        wrappedHistory
      }
      val historyLines = visibleHistory.takeRight(conversationHeight)

      for ((msg, i) <- historyLines.zipWithIndex) {
        screen.put(0, i + 2, border.vertical + " ")
        
        val paddedCells = msg.cells.take(width - 4).padTo(width - 4, Cell(" "))
        paddedCells.zipWithIndex.foreach { case (c, j) =>
          screen.put(2 + j, i + 2, c)
        }

        screen.put(width - 2, i + 2, " " + border.vertical)
      }

      for (i <- historyLines.length until conversationHeight) {
        screen.put(0, i + 2, border.vertical + " " * (width - 2) + border.vertical)
      }
    }

    private def renderThought(screen: Screen, config: LayoutConfig, currentThought: Option[String], width: Int, startRow: Int, thoughtHeight: Int): Unit = {
      val border = config.borderStyle
      val prefix = config.thoughtPrefix
      val maxThoughtWidth = Math.max(1, width - prefix.length - 4)

      val thoughtLines: List[String] = currentThought match {
        case Some(thought) if thought.trim.nonEmpty =>
          val lines = Layout.wrapText(thought.trim, maxThoughtWidth)
          lines.take(thoughtHeight)
        case _ =>
          List.empty
      }

      for (i <- 0 until thoughtHeight) {
        val row = startRow + i
        if (i < thoughtLines.length) {
          val line = thoughtLines(i)
          screen.put(0, row, border.vertical + " ")
          if (i == 0) {
            screen.put(2, row, prefix, Color.BrightCyan)
            screen.put(2 + prefix.length, row, line, Color.Default)
            val remainingSpaces = Math.max(0, width - 4 - prefix.length - line.length)
            screen.put(2 + prefix.length + line.length, row, " " * remainingSpaces + " " + border.vertical)
          } else {
            val indent = " " * prefix.length
            screen.put(2, row, indent + line, Color.Default)
            val remainingSpaces = Math.max(0, width - 4 - prefix.length - line.length)
            screen.put(2 + prefix.length + line.length, row, " " * remainingSpaces + " " + border.vertical)
          }
        } else {
          screen.put(0, row, border.vertical + " " * (width - 2) + border.vertical)
        }
      }
    }

    private def renderPrompt(screen: Screen, config: LayoutConfig, prompt: String, width: Int, row: Int): Unit = {
      val border = config.borderStyle
      val prefix = config.promptPrefix
      val maxPromptWidth = Math.max(1, width - prefix.length - 4)
      val visiblePrompt = if (prompt.length > maxPromptWidth) {
        prompt.takeRight(maxPromptWidth)
      } else {
        prompt
      }
      val remaining = maxPromptWidth - visiblePrompt.length
      
      screen.put(0, row, border.vertical + s" $prefix")
      screen.put(2 + prefix.length, row, visiblePrompt)
      screen.put(2 + prefix.length + visiblePrompt.length, row, " " * remaining + " " + border.vertical)
    }

    private def renderFooter(screen: Screen, config: LayoutConfig, width: Int, row: Int): Unit = {
      val border = config.borderStyle
      screen.put(0, row, border.bottomLeft + border.horizontal * (width - 2) + border.bottomRight)
    }
  }
}
