package com.tark.ports.shared.react

import cats.syntax.all.*
import com.tark.domain.react.{CallTool, Finish, ReActAction}
import io.circe.parser.*

object ReActParser {

  private def stripMarkdownCodeBlocks(s: String): String = {
    val trimmed = s.trim
    if (trimmed.startsWith("```json") && trimmed.endsWith("```")) {
      trimmed.substring(7, trimmed.length - 3).trim
    } else if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
      trimmed.substring(3, trimmed.length - 3).trim
    } else {
      trimmed
    }
  }

  /**
   * Parses the LLM's response to extract (Thought, Action).
   * Supports both structured JSON objects (conforming to forced json schema) and legacy plain text.
   */
  def parseResponse(response: String): Either[String, (String, ReActAction)] = {
    val stripped = stripMarkdownCodeBlocks(response)
    if (stripped.startsWith("{") && stripped.endsWith("}")) {
      // Parse as forced ReAct JSON response
      parse(stripped) match {
        case Left(err) => 
          Left(s"Failed to parse ReAct JSON response: ${err.message}. Raw: '$stripped'")
        case Right(json) =>
          val cursor = json.hcursor
          val thought = cursor.get[String]("thought").getOrElse("")
          
          val optAction = cursor.get[io.circe.Json]("action") match {
            case Right(actJson) =>
              val actCursor = actJson.hcursor
              val name = actCursor.get[String]("name").getOrElse("")
              val args = actCursor.get[io.circe.Json]("arguments").getOrElse(io.circe.Json.obj())
              Some(CallTool(name, args))
            case Left(_) =>
              None
          }

          val optFinish = cursor.get[String]("finish") match {
            case Right(finishText) => Some(Finish(finishText))
            case Left(_) => None
          }

          optAction.orElse(optFinish) match {
            case Some(action) => Right((thought, action))
            case None => Left("Could not find either 'action' or 'finish' in ReAct JSON response.")
          }
      }
    } else {
      // Legacy plain text line-by-line parsing
      val trimmed = response.trim
      val lines = trimmed.split("\n")
      
      val thoughtIndex = lines.indexWhere(_.trim.startsWith("Thought:"))
      val actionIndex = lines.indexWhere(_.trim.startsWith("Action:"))
      val finishIndex = lines.indexWhere(_.trim.startsWith("Finish:"))

      if (thoughtIndex == -1) {
        Left("Could not find 'Thought:' prefix in LLM response.")
      } else {
        val actionOrFinishIndex = if (actionIndex != -1) actionIndex else finishIndex
        if (actionOrFinishIndex == -1) {
          Left("Could not find either 'Action:' or 'Finish:' prefix in LLM response.")
        } else if (actionOrFinishIndex <= thoughtIndex) {
          Left("'Action:' or 'Finish:' must appear after 'Thought:'.")
        } else {
          // Extract thought
          val thoughtLines = lines.slice(thoughtIndex, actionOrFinishIndex).map { line =>
            val trimmed = line.trim
            if (trimmed.startsWith("Thought:")) trimmed.substring(8).trim else trimmed
          }
          val thought = thoughtLines.mkString("\n").trim

          if (actionIndex != -1) {
            // Extract Action: name json_args
            val actionLine = lines(actionIndex).trim.substring(7).trim
            val spaceIdx = actionLine.indexOf(' ')
            if (spaceIdx == -1) {
              Left("Action format should be 'Action: <tool_name> <json_arguments>'.")
            } else {
              val toolName = actionLine.substring(0, spaceIdx).trim
              val jsonStr = actionLine.substring(spaceIdx).trim
              
              parse(jsonStr) match {
                case Left(err) => Left(s"Failed to parse Action JSON arguments: ${err.message}. Raw JSON: '$jsonStr'")
                case Right(json) => Right((thought, CallTool(toolName, json)))
              }
            }
          } else {
            // Extract Finish: output
            val finishLines = lines.slice(finishIndex, lines.length).map { line =>
              val trimmed = line.trim
              if (trimmed.startsWith("Finish:")) trimmed.substring(7).trim else trimmed
            }
            val finishText = finishLines.mkString("\n").trim
            Right((thought, Finish(finishText)))
          }
        }
      }
    }
  }
}
