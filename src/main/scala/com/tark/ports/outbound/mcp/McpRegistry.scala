package com.tark.ports.outbound.mcp

import com.tark.domain.tool.{McpToolDefinition, ToolResult}

trait McpRegistry[F[_]] {
  /**
   * Retrieves all tools exposed by all registered and running MCP servers.
   */
  def getTools: F[List[McpToolDefinition]]

  /**
   * Executes a tool call on the registered MCP server that exposes the tool.
   */
  def callTool(toolName: String, argumentsJson: String): F[ToolResult]
}
