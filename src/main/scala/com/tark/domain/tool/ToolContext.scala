package com.tark.domain.tool

import com.tark.domain.context.Context

case class ToolContext(
                      context: Context,
                      args: Map[String, String],
                      executionId: String
                      )
