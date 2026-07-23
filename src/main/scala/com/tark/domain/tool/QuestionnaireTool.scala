package com.tark.domain.tool

import cats.syntax.all.*
import io.circe.Json
import io.circe.parser

object QuestionnaireTool {
  val definition: ToolDefinition = ToolDefinition.Questionnaire

  def parseArguments(arguments: String): Either[String, (String, List[String])] = {
    parser.parse(arguments).leftMap(_.getMessage).flatMap { json =>
      val cursor = json.hcursor
      val questionOpt = cursor.get[String]("question").toOption
        .orElse(cursor.get[String]("questionnaire").toOption)
        .orElse(cursor.get[String]("prompt").toOption)

      val optionsOpt = cursor.get[List[String]]("options").toOption

      (questionOpt, optionsOpt) match {
        case (Some(q), Some(opts)) =>
          if (opts.isEmpty) Left("Tool argument 'options' must have at least one option.")
          else Right((q.trim, opts.map(_.trim)))
        case (None, _) =>
          Left("Tool argument 'question' (or 'questionnaire' / 'prompt') is missing or empty.")
        case (_, None) =>
          Left("Tool argument 'options' is missing.")
      }
    }
  }
}
