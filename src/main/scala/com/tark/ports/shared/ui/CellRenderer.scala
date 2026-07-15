package com.tark.ports.shared.ui

import com.tark.domain.ui.{Cell, Color, Style}

trait CellRenderer[C, +T] {
  def render(cell: C): T
}

object CellRenderer {
  given CellRenderer[Cell, String] with {
    private def ansiCode(color: Color, isBackground: Boolean): String = {
      val offset = if (isBackground) 10 else 0
      color match {
        case Color.Default       => if (isBackground) "49" else "39"
        case Color.Black         => s"${30 + offset}"
        case Color.Red           => s"${31 + offset}"
        case Color.Green         => s"${32 + offset}"
        case Color.Yellow        => s"${33 + offset}"
        case Color.Blue          => s"${34 + offset}"
        case Color.Magenta       => s"${35 + offset}"
        case Color.Cyan          => s"${36 + offset}"
        case Color.White         => s"${37 + offset}"
        case Color.BrightBlack   => s"${90 + offset}"
        case Color.BrightRed     => s"${91 + offset}"
        case Color.BrightGreen   => s"${92 + offset}"
        case Color.BrightYellow  => s"${93 + offset}"
        case Color.BrightBlue    => s"${94 + offset}"
        case Color.BrightMagenta => s"${95 + offset}"
        case Color.BrightCyan    => s"${96 + offset}"
        case Color.BrightWhite   => s"${97 + offset}"
      }
    }

    private def ansiCode(style: Style): String = style match {
      case Style.Bold      => "1"
      case Style.Dim       => "2"
      case Style.Italic    => "3"
      case Style.Underline => "4"
      case Style.Reverse   => "7"
    }

    override def render(cell: Cell): String = {
      val fg = ansiCode(cell.fg, isBackground = false)
      val bg = ansiCode(cell.bg, isBackground = true)
      val styles = cell.styles.map(ansiCode).toList
      val codes = (fg :: bg :: styles).mkString(";")
      s"\u001b[${codes}m${cell.glyph}\u001b[0m"
    }
  }
}
