package com.tark.ui

import com.tark.ports.AgentBackend
import fs2.Stream

sealed trait AgentAction[F[_]]

object AgentAction:
  final case class Log[F[_]](text: String) extends AgentAction[F]
  final case class AssistantDelta[F[_]](text: String) extends AgentAction[F]
  final case class AssistantEnd[F[_]]() extends AgentAction[F]
  final case class SystemMessage[F[_]](text: String) extends AgentAction[F]
  final case class ClearScreen[F[_]]() extends AgentAction[F]
  final case class Exit[F[_]]() extends AgentAction[F]
  final case class StatusUpdate[F[_]](text: String) extends AgentAction[F]
  final case class RequestChoice[F[_]](
    prompt: String,
    options: List[String],
    allowCustom: Boolean,
    onSelected: String => Stream[F, AgentAction[F]]
  ) extends AgentAction[F]

final case class AgentTask[F[_]](
  description: Option[String],
  action: Stream[F, AgentAction[F]]
)

trait AgentFrontend[F[_]]:
  def handleInput(input: String)(using AgentBackend[F]): F[Unit]
