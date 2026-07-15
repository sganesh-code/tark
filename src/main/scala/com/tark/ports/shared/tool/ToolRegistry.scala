package com.tark.ports.shared.tool

import com.tark.domain.tool.Tool

trait ToolRegistry[C] {
  def register(context: C, tool: Tool): C
  def lookup(context: C, toolName: String): Option[Tool]
}
