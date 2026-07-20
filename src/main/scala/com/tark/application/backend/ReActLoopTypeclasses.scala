package com.tark.application.backend

import cats.effect.{Ref, Sync}
import com.tark.domain.Interaction
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolResult}
import com.tark.ports.outbound.backend.LLMResponse
import com.tark.ui.{AgentAction, AgentTask}
import fs2.Stream

/**
 * Generic typeclass for converting a domain representation A into an OpenAIMessage.
 * This unifies all user, system, tool, and assistant message serialization logic.
 */
trait ToMessage[A] {
  def toMessage(item: A): OpenAIMessage
}

object ToMessage {
  // Mapping a raw tool execution tuple (ToolCall, ToolResult) into its tool message representation
  given ToMessage[(ToolCall, ToolResult)] with {
    override def toMessage(item: (ToolCall, ToolResult)): OpenAIMessage = {
      val (toolCall, result) = item
      OpenAIMessage(
        role = "tool",
        content = Some(result.content),
        tool_call_id = Some(toolCall.id)
      )
    }
  }

  // Mapping an assistant response into its assistant message representation
  given ToMessage[LLMResponse[ToolCall]] with {
    override def toMessage(response: LLMResponse[ToolCall]): OpenAIMessage =
      OpenAIMessage(
        role = "assistant",
        content = if (response.content.nonEmpty) Some(response.content) else None,
        tool_calls = if (response.results.nonEmpty) Some(response.results) else None
      )
  }
}

/**
 * Generic typeclass for converting a domain representation A (along with contextual data C)
 * into a persistent history Interaction.
 */
trait ToInteraction[A, C] {
  def toInteraction(item: A, context: C): Interaction
}

object ToInteraction {
  // Mapping a raw tool execution tuple into an Interaction
  given ToInteraction[(ToolCall, ToolResult), (Long, Int)] with {
    override def toInteraction(item: (ToolCall, ToolResult), ctx: (Long, Int)): Interaction = {
      val (toolCall, result) = item
      val (baseTimestamp, index) = ctx
      Interaction(
        id = s"interaction_${baseTimestamp}_$index",
        input = toolCall.function.arguments,
        output = result.content,
        timestamp = baseTimestamp + index,
        toolName = toolCall.function.name
      )
    }
  }

  // Mapping an LLM final answer into an Interaction
  given ToInteraction[LLMResponse[ToolCall], (String, Long)] with {
    override def toInteraction(response: LLMResponse[ToolCall], ctx: (String, Long)): Interaction = {
      val (input, timestamp) = ctx
      Interaction(
        id = s"interaction_$timestamp",
        input = input,
        output = response.content,
        timestamp = timestamp,
        toolName = "llm_completion"
      )
    }
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
