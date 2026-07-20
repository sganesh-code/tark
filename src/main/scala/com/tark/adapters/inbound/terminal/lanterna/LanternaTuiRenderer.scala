package com.tark.adapters.inbound.terminal.lanterna

import com.googlecode.lanterna.TerminalPosition
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.screen.Screen
import com.tark.ui.{TerminalStyle, TerminalColor}

object LanternaTuiRenderer {
  def render(screen: Screen, state: TuiState): Unit = screen.synchronized {
    screen.clear()
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
    for (col <- 0 until width) {
      tg.putString(col, mainAreaHeight - 1, "─")
    }

    // Draw vertical separator between left log and right panel
    for (row <- 0 until (mainAreaHeight - 1)) {
      tg.putString(splitCol, row, "│")
    }
    
    // Joint character
    tg.putString(splitCol, mainAreaHeight - 1, "┴")

    // 2. Wrap and Draw Log Scrollback (Left Pane)
    val wrappedLogLines = state.scrollback.flatMap { logLine =>
      wrapLine(logLine, logWidth).map(text => (text, logLine.style))
    }

    val maxLogRows = mainAreaHeight - 1
    val logToDraw = wrappedLogLines.takeRight(maxLogRows)
    logToDraw.zipWithIndex.foreach { case ((text, style), idx) =>
      val row = maxLogRows - logToDraw.size + idx
      tg.setForegroundColor(LanternaStyleMapper.toLanternaColor(style.foreground))
      tg.enableModifiers(LanternaStyleMapper.toLanternaSGRs(style)*)
      tg.putString(0, row, text)
      tg.clearModifiers()
    }

    // 3. Draw Active Context Panel (Right Pane)
    val panelToDraw = state.activePanelLines.flatMap { line =>
      wrapText(line, rightWidth)
    }.take(mainAreaHeight - 1)

    panelToDraw.zipWithIndex.foreach { case (line, idx) =>
      val col = splitCol + 1
      tg.setForegroundColor(TextColor.ANSI.DEFAULT)
      tg.putString(col, idx, line)
    }

    // 4. Draw Status Bar
    val spinnerPart = if (state.spinnerFrame.nonEmpty) s"${state.spinnerFrame} " else ""
    val statusText = state.statusText
    val combinedStatus = s"$spinnerPart$statusText"
    tg.setForegroundColor(TextColor.ANSI.YELLOW)
    tg.putString(0, height - 2, combinedStatus)

    // 5. Draw Active Prompt
    tg.setForegroundColor(TextColor.ANSI.GREEN)
    tg.putString(0, height - 1, state.activePrompt)
    
    tg.setForegroundColor(TextColor.ANSI.DEFAULT)
    tg.putString(state.activePrompt.length, height - 1, state.activeInput)

    // Set cursor position on the input line
    val cursorCol = state.activePrompt.length + state.cursorPosition
    screen.setCursorPosition(new TerminalPosition(cursorCol, height - 1))

    screen.refresh()
  }

  private def wrapLine(line: LanternaLogLine, width: Int): Vector[String] = {
    val prefix = line.sender.map(s => s"[$s] ").getOrElse("")
    val fullText = prefix + line.text
    wrapText(fullText, width)
  }

  private def wrapText(text: String, width: Int): Vector[String] = {
    if (width <= 0) Vector(text)
    else {
      val paragraphs = text.split("\n", -1).toVector
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
