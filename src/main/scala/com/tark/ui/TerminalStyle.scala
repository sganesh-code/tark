package com.tark.ui

enum TerminalColor:
  case Default, Black, Red, Green, Yellow, Blue, Magenta, Cyan, White

final case class TerminalStyle(
  foreground: TerminalColor = TerminalColor.Default,
  bold: Boolean = false,
  italic: Boolean = false
)

object TerminalStyle:
  val Default: TerminalStyle = TerminalStyle()
  val Agent: TerminalStyle = TerminalStyle(foreground = TerminalColor.Cyan, italic = true)
  val System: TerminalStyle = TerminalStyle(foreground = TerminalColor.Yellow)
  val Error: TerminalStyle = TerminalStyle(foreground = TerminalColor.Red)
