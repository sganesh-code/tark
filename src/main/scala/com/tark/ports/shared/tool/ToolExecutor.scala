package com.tark.ports.shared.tool

import com.tark.domain.tool.ToolContext

trait ToolExecutor[T] {
  def execute(tool: T, context: ToolContext): String
  def validate(tool: T): Boolean
}
