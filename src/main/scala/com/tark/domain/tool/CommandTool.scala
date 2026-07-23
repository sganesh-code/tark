package com.tark.domain.tool

import cats.syntax.all.*
import io.circe.parser

object CommandTool {
  val definition: ToolDefinition = ToolDefinition.Command

  private def stripQuotes(s: String): String = {
    val trimmed = s.trim
    if (
      (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
      (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
      (trimmed.startsWith("`") && trimmed.endsWith("`"))
    ) trimmed.drop(1).dropRight(1).trim
    else trimmed
  }

  def commandFrom(toolCall: ToolCall): Either[String, String] =
    parser.parse(toolCall.function.arguments).leftMap(_.getMessage).flatMap { json =>
      json.hcursor.get[Option[String]]("command").leftMap(_.getMessage).flatMap {
        case Some(command) => Right(stripQuotes(command))
        case None => Left("Tool argument 'command' is missing.")
      }
    }.flatMap {
      case "" => Left("Tool argument 'command' is empty.")
      case command => Right(command)
    }
}
