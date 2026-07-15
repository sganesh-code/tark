package com.tark.domain.tool

case class ToolResult(
                     success: Boolean,
                     data: Option[String],
                     error: Option[String]
                     )
