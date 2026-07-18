package com.tark.ports.outbound.backend

import cats.kernel.Monoid
import com.tark.domain.tool.{OpenAIMessage, ToolDefinition}

case class PromptEnrichment(
                           systemInstructions: List[String] = List.empty,
                           injectedMessages: List[OpenAIMessage] = List.empty,
                           additionalTools: List[ToolDefinition] = List.empty
                           )

object PromptEnrichment {
  given Monoid[PromptEnrichment] with {
    def empty: PromptEnrichment = PromptEnrichment()

    override def combine(x: PromptEnrichment, y: PromptEnrichment): PromptEnrichment =
      PromptEnrichment(
        systemInstructions = x.systemInstructions ++ y.systemInstructions,
        injectedMessages = x.injectedMessages ++ y.injectedMessages,
        additionalTools = x.additionalTools ++ y.additionalTools
      )
  }
}
