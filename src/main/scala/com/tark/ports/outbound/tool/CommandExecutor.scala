package com.tark.ports.outbound.tool

import com.tark.domain.context.Context
import com.tark.domain.tool.{ToolCall, ToolDefinition, ToolResult}

trait CommandExecutor[F[_]] {
  def definition: ToolDefinition
  def execute(context: Context, toolCall: ToolCall): F[ToolResult]
}
