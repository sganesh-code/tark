package com.tark.application.tools

import com.tark.domain.tool.{Tool, ToolContext}
import com.tark.ports.shared.tool.ToolExecutor

object ToolInstances {
  given ToolExecutor[Tool] with {

    override def execute(tool: Tool, context: ToolContext): String =
      tool.execute(context)

    override def validate(tool: Tool): Boolean =
      tool.name != null && tool.execute != null
  }
}
