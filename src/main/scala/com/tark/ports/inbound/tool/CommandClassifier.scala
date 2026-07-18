package com.tark.ports.inbound.tool

sealed trait CommandType
case object SLASH_COMMAND extends CommandType
case object HARNESS_COMMAND extends CommandType
case object REACT_COMMAND extends CommandType

object CommandClassifier {
  def classify(input: String): CommandType = {
    val trimmed = input.trim
    if trimmed == "/run" || trimmed.startsWith("/run ") then HARNESS_COMMAND
    else if trimmed.startsWith("/") then SLASH_COMMAND
    else REACT_COMMAND
  }
}
