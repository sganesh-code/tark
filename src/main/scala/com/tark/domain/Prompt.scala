package com.tark.domain

import com.tark.domain.tool.{OpenAIMessage, ToolDefinition}

case class Prompt(
                   messages: List[OpenAIMessage],
                   availableTools: List[ToolDefinition]
                 )
