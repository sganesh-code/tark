package com.tark.adapters.inbound.terminal.lanterna

import com.tark.ui.TerminalStyle

case class LanternaLogLine(sender: Option[String], text: String, style: TerminalStyle)

case class TuiState(
  scrollback: Vector[LanternaLogLine] = Vector.empty,
  statusText: String = "",
  spinnerFrame: String = "",
  activePanelLines: Vector[String] = Vector.empty,
  activePrompt: String = "",
  activeInput: String = "",
  cursorPosition: Int = 0,
  inlineOpen: Boolean = false
)
