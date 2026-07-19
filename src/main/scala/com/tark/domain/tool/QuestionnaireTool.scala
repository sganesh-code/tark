package com.tark.domain.tool

import cats.syntax.all.*
import io.circe.Json
import io.circe.parser

object QuestionnaireTool {
  val definition: ToolDefinition = ToolDefinition(
    `type` = "function",
    function = OpenAIFunction(
      name = "questionnaire",
      description = "Present a questionnaire/question with several options to the user and receive their selection to proceed.",
      parameters = OpenAIFunctionParams.Custom(
        Json.obj(
          "type" -> Json.fromString("object"),
          "properties" -> Json.obj(
            "question" -> Json.obj(
              "type" -> Json.fromString("string"),
              "description" -> Json.fromString("The question or instruction to present to the user.")
            ),
            "options" -> Json.obj(
              "type" -> Json.fromString("array"),
              "items" -> Json.obj(
                "type" -> Json.fromString("string")
              ),
              "description" -> Json.fromString("List of answer options for the user to select from. Must not be empty.")
            )
          ),
          "required" -> Json.arr(Json.fromString("question"), Json.fromString("options"))
        )
      )
    )
  )

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
