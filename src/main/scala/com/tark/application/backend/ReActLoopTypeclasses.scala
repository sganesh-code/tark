package com.tark.application.backend

import cats.effect.{Ref, Sync}
import com.tark.domain.Interaction
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolResult}
import com.tark.ports.outbound.backend.LLMResponse
import com.tark.ui.{AgentAction, AgentTask}
import fs2.Stream

/**
 * Typeclass for mapping a raw tool execution tuple (ToolCall, ToolResult) into its corresponding
 * conversation message (OpenAIMessage) and persistent history interaction (Interaction).
 */
trait ToolInteractionMapper[T] {
  def toMessage(item: T): OpenAIMessage
  def toInteraction(item: T, baseTimestamp: Long, index: Int): Interaction
}

object ToolInteractionMapper {
  given ToolInteractionMapper[(ToolCall, ToolResult)] with {
    override def toMessage(item: (ToolCall, ToolResult)): OpenAIMessage = {
      val (toolCall, result) = item
      OpenAIMessage(
        role = "tool",
        content = Some(result.content),
        tool_call_id = Some(toolCall.id)
      )
    }

    override def toInteraction(item: (ToolCall, ToolResult), baseTimestamp: Long, index: Int): Interaction = {
      val (toolCall, result) = item
      Interaction(
        id = s"interaction_${baseTimestamp}_$index",
        input = toolCall.function.arguments,
        output = result.content,
        timestamp = baseTimestamp + index,
        toolName = toolCall.function.name
      )
    }
  }
}

/**
 * Typeclass for mapping an LLM response of final answer into a history Interaction.
 */
trait CompletionInteractionMapper[T] {
  def toInteraction(response: T, input: String, timestamp: Long): Interaction
}

object CompletionInteractionMapper {
  given CompletionInteractionMapper[LLMResponse[ToolCall]] with {
    override def toInteraction(response: LLMResponse[ToolCall], input: String, timestamp: Long): Interaction =
      Interaction(
        id = s"interaction_$timestamp",
        input = input,
        output = response.content,
        timestamp = timestamp,
        toolName = "llm_completion"
      )
  }
}

/**
 * Typeclass for mapping an LLM response into an assistant OpenAIMessage.
 */
trait AssistantMessageMapper[T] {
  def toAssistantMessage(response: T): OpenAIMessage
}

object AssistantMessageMapper {
  given AssistantMessageMapper[LLMResponse[ToolCall]] with {
    override def toAssistantMessage(response: LLMResponse[ToolCall]): OpenAIMessage =
      OpenAIMessage(
        role = "assistant",
        content = if (response.content.nonEmpty) Some(response.content) else None,
        tool_calls = if (response.results.nonEmpty) Some(response.results) else None
      )
  }
}

/**
 * Typeclass for compiling a tool call execution stream into a structured AgentTask.
 */
trait ToolTaskBuilder[F[_]] {
  def buildTask(
    toolCall: ToolCall,
    actionStream: Stream[F, AgentAction[F]],
    resultRef: Ref[F, Option[ToolResult]],
    accumulateResult: ToolResult => F[Unit]
  ): AgentTask[F]
}

object ToolTaskBuilder {
  given [F[_]: Sync]: ToolTaskBuilder[F] with {
    override def buildTask(
      toolCall: ToolCall,
      actionStream: Stream[F, AgentAction[F]],
      resultRef: Ref[F, Option[ToolResult]],
      accumulateResult: ToolResult => F[Unit]
    ): AgentTask[F] = {
      AgentTask(
        description = None,
        action =
          Stream.emit(AgentAction.ToolCallStart[F](toolCall.function.name, toolCall.function.arguments)) ++
            actionStream ++
            Stream.eval(resultRef.get).flatMap {
              case Some(result) =>
                Stream.eval(accumulateResult(result)).drain ++
                  Stream.emit(AgentAction.ToolCallOutput[F](result.content))
              case None =>
                Stream.empty
            } ++
            Stream.emit(AgentAction.ToolCallEnd[F]())
      )
    }
  }
}
