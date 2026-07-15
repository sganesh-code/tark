package com.tark.ports.shared.tool

import com.tark.domain.*
import com.tark.domain.tool.{FunctionDefinition, FunctionParameters, FunctionProperty, Tool, ToolDefinition}
import com.tark.ports.shared.tool.ToToolDescription

trait ToToolDescription[T] {
  def describe(tool: T): ToolDefinition
}

object ToToolDescription {
  given default: ToToolDescription[Tool] with {
    override def describe(tool: Tool): ToolDefinition = {
      val description = if (tool.name == "command_executor") {
        "Executes shell commands in a secure Docker sandbox terminal environment"
      } else {
        "Executes custom tool operations"
      }
      
      val properties = if (tool.name == "command_executor") {
        Map("command" -> FunctionProperty("string", "The exact shell command to execute, e.g. 'ls -la'"))
      } else {
        Map("arguments" -> FunctionProperty("string", "Tool call arguments"))
      }
      
      val required = properties.keys.toList

      ToolDefinition(
        function = FunctionDefinition(
          name = tool.name,
          description = description,
          parameters = FunctionParameters(
            properties = properties,
            required = required
          )
        )
      )
    }
  }
}
