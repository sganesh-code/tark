package com.tark.ui

enum InputResult:
  case Line(text: String)
  case Cancelled
  case Exit

trait TerminalReader[F[_]]:
  def readLine(promptPrefix: String): F[InputResult]
  def readChoice(prompt: String, options: List[String], allowCustom: Boolean = false): F[String]

trait TerminalWriter[F[_]]:
  def printAbove(sender: String, message: String, style: TerminalStyle = TerminalStyle.Default): F[Unit]
  def startInline(sender: String, style: TerminalStyle = TerminalStyle.Default): F[Unit]
  def appendInline(message: String, style: TerminalStyle = TerminalStyle.Default): F[Unit]
  def finishInline(): F[Unit]
  def printSystemMessage(message: String, style: TerminalStyle = TerminalStyle.Default): F[Unit]
  def printLine(message: String): F[Unit]
  def clearScreen(): F[Unit]
  def flush(): F[Unit]
  def printPanel(panelText: String): F[Unit] = printLine(panelText)
