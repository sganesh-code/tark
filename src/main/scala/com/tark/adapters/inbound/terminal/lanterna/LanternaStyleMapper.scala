package com.tark.adapters.inbound.terminal.lanterna

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.tark.ui.{TerminalColor, TerminalStyle}

object LanternaStyleMapper {
  def toLanternaColor(color: TerminalColor): TextColor = color match {
    case TerminalColor.Default => TextColor.ANSI.DEFAULT
    case TerminalColor.Black   => TextColor.ANSI.BLACK
    case TerminalColor.Red     => TextColor.ANSI.RED
    case TerminalColor.Green   => TextColor.ANSI.GREEN
    case TerminalColor.Yellow  => TextColor.ANSI.YELLOW
    case TerminalColor.Blue    => TextColor.ANSI.BLUE
    case TerminalColor.Magenta => TextColor.ANSI.MAGENTA
    case TerminalColor.Cyan    => TextColor.ANSI.CYAN
    case TerminalColor.White   => TextColor.ANSI.WHITE
  }

  def toLanternaSGRs(style: TerminalStyle): Array[SGR] = {
    val sgrs = scala.collection.mutable.ArrayBuffer.empty[SGR]
    if (style.bold) sgrs += SGR.BOLD
    if (style.italic) sgrs += SGR.ITALIC
    sgrs.toArray
  }
}
