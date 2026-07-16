package com.tark.ports.outbound.backend

import cats.Monad
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolDefinition}

trait LLMClient[F[_]: Monad, I, A]:
  def chat(prompt: I): F[A]

case class Prompt(
  messages: List[OpenAIMessage],
  availableTools: List[ToolDefinition]
)

case class LLMResponse[A](
  content: String,
  results: List[A]
)

type LlmClient[F[_]] = LLMClient[F, Prompt, LLMResponse[ToolCall]]
