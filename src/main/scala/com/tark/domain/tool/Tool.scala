package com.tark.domain.tool

import com.tark.domain.tool.{Tool, ToolContext, ToolType}

sealed trait ToolType
object ToolType {
  case object GenericTool extends ToolType
  case object CommandTool extends ToolType
}

case class Tool(
               name: String,
               execute: ToolContext => String,
               toolType: ToolType = ToolType.GenericTool
               )
