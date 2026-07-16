package com.tark.domain.react

sealed trait ReActAction

case class CallTool(toolName: String, arguments: String) extends ReActAction

case class Finish(finalAnswer: String) extends ReActAction

case class ReActStep(
  thought: String,
  action: ReActAction,
  observation: Option[String]
)
