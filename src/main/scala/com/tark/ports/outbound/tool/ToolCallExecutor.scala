package com.tark.ports.outbound.tool

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.domain.context.Context
import com.tark.domain.tool.{QuestionnaireTool, ToolCall, ToolResult}
import com.tark.ui.AgentAction
import fs2.Stream

trait ToolCallExecutor[F[_]] {
  def execute(context: Context, toolCall: ToolCall, resultRef: Ref[F, Option[ToolResult]]): Stream[F, AgentAction[F]]
}

class DefaultToolCallExecutor[F[_]: Sync](commandExecutor: CommandExecutor[F]) extends ToolCallExecutor[F] {
  override def execute(context: Context, toolCall: ToolCall, resultRef: Ref[F, Option[ToolResult]]): Stream[F, AgentAction[F]] = {
    if toolCall.function.name == "command_executor" then
      Stream.eval(
        commandExecutor.execute(context, toolCall).flatMap(res => resultRef.set(Some(res)))
      ).drain
    else if toolCall.function.name == "questionnaire" then
      QuestionnaireTool.parseArguments(toolCall.function.arguments) match {
        case Left(error) =>
          Stream.eval(resultRef.set(Some(ToolResult(s"Questionnaire failed: $error")))).drain ++
            Stream.emit(AgentAction.SystemMessage(s"Questionnaire error: $error"))
        case Right((question, options)) =>
          Stream.emit(
            AgentAction.RequestChoice[F](
              prompt = question,
              options = options,
              allowCustom = false,
              onSelected = choice => Stream.eval(resultRef.set(Some(ToolResult(choice)))).drain
            )
          )
      }
    else
      Stream.eval(resultRef.set(Some(ToolResult(s"Tool '${toolCall.function.name}' is not available.")))).drain
  }
}
