package com.tark.domain

import com.tark.domain.tool.OpenAIMessage

/**
 * Encapsulates the complete contextual state required to evaluate plan checklist progress,
 * as defined in agent_harness_research_grounding.md.
 */
case class ProgressContext(
  goal: String,
  activeStep: String,
  conversation: List[OpenAIMessage]
)
