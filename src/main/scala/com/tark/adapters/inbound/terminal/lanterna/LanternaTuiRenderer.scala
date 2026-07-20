package com.tark.adapters.inbound.terminal.lanterna

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.screen.Screen
import com.tark.ui.{TerminalStyle, TerminalColor}

object LanternaTuiRenderer {
  def render(screen: Screen, state: TuiState): Unit = screen.synchronized {
    val size = screen.getTerminalSize
    val width = size.getColumns
    val height = size.getRows

    if (width < 30 || height < 6) {
      val tg = screen.newTextGraphics()
      tg.setForegroundColor(TextColor.ANSI.RED)
      tg.putString(0, 0, "Terminal too small!")
      screen.refresh()
      return
    }

    val splitCol = (width * 0.65).toInt
    val logWidth = splitCol - 2
    val rightWidth = width - splitCol - 2
    val mainAreaHeight = height - 2

    // 1. Draw Split Borders
    val tg = screen.newTextGraphics()
    tg.setForegroundColor(TextColor.ANSI.DEFAULT)
    
    // Draw horizontal separator above status bar
    tg.putString(0, mainAreaHeight - 1, "─" * width)

    // Draw vertical separator between left log and right panel
    for (row <- 0 until (mainAreaHeight - 1)) {
      tg.putString(splitCol, row, "│")
    }
    
    // Joint character
    tg.putString(splitCol, mainAreaHeight - 1, "┴")

    // 2. Wrap and Draw Log Scrollback (Left Pane) with scrollOffset and activeMenu support
    val wrappedLogLines = state.scrollback.flatMap { logLine =>
      wrapLine(logLine, logWidth).map(text => (text, logLine.style))
    }

    val wrappedMenuLines = state.activeMenuLines.flatMap { menuLine =>
      wrapLine(menuLine, logWidth).map(text => (text, menuLine.style))
    }

    val totalLeftLines = wrappedLogLines ++ wrappedMenuLines

    val maxLogRows = mainAreaHeight - 1
    val maxScroll = math.max(0, totalLeftLines.size - maxLogRows)
    val currentScroll = math.min(state.scrollOffset, maxScroll)
    val sliceStart = maxScroll - currentScroll
    val logToDraw = totalLeftLines.slice(sliceStart, sliceStart + maxLogRows)

    for (idx <- 0 until maxLogRows) {
      val row = idx
      if (idx < logToDraw.size) {
        val (text, style) = logToDraw(idx)
        val cleanText = text.padTo(logWidth, ' ').take(logWidth)
        tg.setForegroundColor(LanternaStyleMapper.toLanternaColor(style.foreground))
        tg.enableModifiers(LanternaStyleMapper.toLanternaSGRs(style)*)
        tg.putString(0, row, cleanText)
        tg.clearModifiers()
      } else {
        tg.setForegroundColor(TextColor.ANSI.DEFAULT)
        tg.putString(0, row, " " * logWidth)
      }
    }

    // 3. Draw Active Context Panel (Right Pane)
    val panelToDraw = state.activePanelLines.flatMap { line =>
      wrapText(line, rightWidth)
    }.take(mainAreaHeight - 1)

    for (idx <- 0 until (mainAreaHeight - 1)) {
      val row = idx
      val col = splitCol + 1
      if (idx < panelToDraw.size) {
        val line = panelToDraw(idx)
        val cleanLine = line.padTo(rightWidth, ' ').take(rightWidth)
        tg.setForegroundColor(TextColor.ANSI.DEFAULT)
        tg.putString(col, row, cleanLine)
      } else {
        tg.setForegroundColor(TextColor.ANSI.DEFAULT)
        tg.putString(col, row, " " * rightWidth)
      }
    }

    // 4. Draw Status Bar
    val spinnerPart = if (state.spinnerFrame.nonEmpty) s"${state.spinnerFrame} " else ""
    val statusText = stripAnsi(state.statusText)
    val combinedStatus = s"$spinnerPart$statusText"
    val cleanStatusLine = combinedStatus.padTo(width, ' ').take(width)
    tg.setForegroundColor(TextColor.ANSI.YELLOW)
    tg.putString(0, height - 2, cleanStatusLine)

    // 5. Draw Active Prompt
    val cleanPrompt = stripAnsi(state.activePrompt)
    val cleanInput = stripAnsi(state.activeInput)
    val inputStartCol = cleanPrompt.length

    // Clear prompt row with spaces first to prevent trailing leftovers
    tg.setForegroundColor(TextColor.ANSI.DEFAULT)
    tg.putString(0, height - 1, " " * width)

    tg.setForegroundColor(TextColor.ANSI.GREEN)
    tg.putString(0, height - 1, cleanPrompt)

    if (cleanInput.startsWith("/")) {
      val firstSpace = cleanInput.indexOf(' ')
      if (firstSpace == -1) {
        tg.setForegroundColor(TextColor.ANSI.YELLOW)
        tg.enableModifiers(com.googlecode.lanterna.SGR.BOLD)
        tg.putString(inputStartCol, height - 1, cleanInput)
        tg.clearModifiers()
      } else {
        val cmd = cleanInput.take(firstSpace)
        val args = cleanInput.drop(firstSpace)
        tg.setForegroundColor(TextColor.ANSI.YELLOW)
        tg.enableModifiers(com.googlecode.lanterna.SGR.BOLD)
        tg.putString(inputStartCol, height - 1, cmd)
        tg.clearModifiers()
        
        tg.setForegroundColor(TextColor.ANSI.DEFAULT)
        tg.putString(inputStartCol + firstSpace, height - 1, args)
      }
    } else {
      tg.setForegroundColor(TextColor.ANSI.DEFAULT)
      tg.putString(inputStartCol, height - 1, cleanInput)
    }

    // Set cursor position on the input line
    val cursorCol = cleanPrompt.length + state.cursorPosition
    screen.setCursorPosition(new TerminalPosition(cursorCol, height - 1))

    screen.refresh()
  }

  private def stripAnsi(s: String): String =
    s.replaceAll("\\u001b\\[[;\\d]*[ -/]*[@-~]", "")

  private[lanterna] def wrapLine(line: LanternaLogLine, width: Int): Vector[String] = {
    val prefix = line.sender.map(s => s"[$s] ").getOrElse("")
    val fullText = prefix + line.text
    wrapText(fullText, width)
  }

  private[lanterna] def wrapText(text: String, width: Int): Vector[String] = {
    val cleanText = stripAnsi(text)
    if (width <= 0) Vector(cleanText)
    else {
      val paragraphs = cleanText.split("\n", -1).toVector
      paragraphs.flatMap { p =>
        if (p.isEmpty) Vector("")
        else {
          val words = p.split(" ")
          val (wrapped, current) = words.foldLeft((Vector.empty[String], "")) { case ((acc, curr), word) =>
            if (curr.isEmpty) {
              if (word.length > width) {
                val chunks = word.grouped(width).toVector
                (acc ++ chunks.init, chunks.last)
              } else (acc, word)
            } else {
              val potential = s"$curr $word"
              if (potential.length > width) {
                (acc :+ curr, word)
              } else {
                (acc, potential)
              }
            }
          }
          if (current.isEmpty) wrapped else wrapped :+ current
        }
      }
    }
  }
}
