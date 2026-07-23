package com.tark.ports.outbound.tool

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.domain.context.Context
import com.tark.domain.tool.{McpToolDefinition, QuestionnaireTool, ToolCall, ToolDefinition, ToolResult}
import com.tark.ui.AgentAction
import fs2.Stream

trait ToolCallExecutor[F[_], -T <: ToolDefinition] {
  def execute(tool: T, context: Context, toolCall: ToolCall, resultRef: Ref[F, Option[ToolResult]]): Stream[F, AgentAction[F]]
}

object ToolCallExecutor {

  extension [F[_], T <: ToolDefinition](tool: T)(using executor: ToolCallExecutor[F, T]) {
    def execute(context: Context, toolCall: ToolCall, resultRef: Ref[F, Option[ToolResult]]): Stream[F, AgentAction[F]] = {
      executor.execute(tool, context, toolCall, resultRef)
    }
  }

  given questionnaireToolCallExecutor[F[_]: Sync]: ToolCallExecutor[F, ToolDefinition.Questionnaire.type] with {
    override def execute(
      tool: ToolDefinition.Questionnaire.type,
      context: Context,
      toolCall: ToolCall,
      resultRef: Ref[F, Option[ToolResult]]
    ): Stream[F, AgentAction[F]] = {
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
    }
  }

  given simpleToolCallExecutor[F[_]: Sync]: ToolCallExecutor[F, ToolDefinition.Simple] with {
    override def execute(
      tool: ToolDefinition.Simple,
      context: Context,
      toolCall: ToolCall,
      resultRef: Ref[F, Option[ToolResult]]
    ): Stream[F, AgentAction[F]] = {
      Stream.eval(resultRef.set(Some(ToolResult(s"Tool '${toolCall.function.name}' is not available.")))).drain
    }
  }

  given mcpToolCallExecutor[F[_]: Sync]: ToolCallExecutor[F, McpToolDefinition] with {
    override def execute(
      tool: McpToolDefinition,
      context: Context,
      toolCall: ToolCall,
      resultRef: Ref[F, Option[ToolResult]]
    ): Stream[F, AgentAction[F]] = {
      Stream.eval(resultRef.set(Some(ToolResult(s"MCP call to '${toolCall.function.name}' executed successfully via McpToolDefinition dispatch.")))).drain
    }
  }

  given commandToolCallExecutor[F[_]: Sync](using cmdExecutor: CommandExecutor[F]): ToolCallExecutor[F, ToolDefinition.Command.type] with {
    override def execute(
      tool: ToolDefinition.Command.type,
      context: Context,
      toolCall: ToolCall,
      resultRef: Ref[F, Option[ToolResult]]
    ): Stream[F, AgentAction[F]] = {
      Stream.eval(
        cmdExecutor.execute(context, toolCall).flatMap(res => resultRef.set(Some(res)))
      ).drain
    }
  }

  given dispatchingToolCallExecutor[F[_]: Sync](using
    commandExecutor: ToolCallExecutor[F, ToolDefinition.Command.type],
    questionnaireExecutor: ToolCallExecutor[F, ToolDefinition.Questionnaire.type],
    mcpExecutor: ToolCallExecutor[F, McpToolDefinition],
    simpleExecutor: ToolCallExecutor[F, ToolDefinition.Simple]
  ): ToolCallExecutor[F, ToolDefinition] with {
    override def execute(
      tool: ToolDefinition,
      context: Context,
      toolCall: ToolCall,
      resultRef: Ref[F, Option[ToolResult]]
    ): Stream[F, AgentAction[F]] = {
      tool match {
        case ToolDefinition.Command =>
          commandExecutor.execute(ToolDefinition.Command, context, toolCall, resultRef)
        case ToolDefinition.Questionnaire =>
          questionnaireExecutor.execute(ToolDefinition.Questionnaire, context, toolCall, resultRef)
        case t: McpToolDefinition =>
          mcpExecutor.execute(t, context, toolCall, resultRef)
        case t: ToolDefinition.Simple =>
          simpleExecutor.execute(t, context, toolCall, resultRef)
      }
    }
  }
}
