package com.tark.ports.shared.tool

import com.tark.domain.tool.ToolContext

trait ExecutableTool[F[_], T] {
  def validate(tool: T): Boolean
  def execute(tool: T, context: ToolContext): F[String]
}

object ExecutableTool {
  def fromExecutor[F[_], T](
    suspend: (() => String) => F[String]
  )(using executor: ToolExecutor[T]): ExecutableTool[F, T] =
    new ExecutableTool[F, T] {
      override def validate(tool: T): Boolean =
        executor.validate(tool)

      override def execute(tool: T, context: ToolContext): F[String] =
        suspend(() => executor.execute(tool, context))
    }
}
