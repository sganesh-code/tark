package com.tark.adapters.mcp

import cats.effect.{IO, Resource}
import com.tark.domain.mcp.{McpServer, McpServers}
import com.tark.ports.outbound.mcp.McpRegistry
import io.modelcontextprotocol.client.McpClient as JavaMcpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema

import java.time.Duration
import scala.jdk.CollectionConverters.*

class StdioMcpRegistry(
  clients: Map[String, McpSyncClient],
  toolToClient: Map[String, McpSyncClient],
  cachedTools: List[com.tark.domain.tool.McpToolDefinition]
) extends McpRegistry[IO] {

  override def getTools: IO[List[com.tark.domain.tool.McpToolDefinition]] =
    IO.pure(cachedTools)

  override def callTool(toolName: String, argumentsJson: String): IO[com.tark.domain.tool.ToolResult] = IO {
    toolToClient.get(toolName) match {
      case Some(client) =>
        val argsMap = io.circe.parser.parse(argumentsJson)
          .flatMap(_.as[Map[String, io.circe.Json]])
          .getOrElse(Map.empty)

        val javaArgs = argsMap.map { case (k, v) => k -> jsonToJava(v) }.asJava
        val request = new McpSchema.CallToolRequest(toolName, javaArgs)
        val result = client.callTool(request)

        val text = result.content().asScala.collect {
          case textContent: McpSchema.TextContent => textContent.text()
        }.mkString("\n")

        com.tark.domain.tool.ToolResult(text)

      case None =>
        com.tark.domain.tool.ToolResult(s"Error: MCP Tool '$toolName' is not registered.")
    }
  }

  private def jsonToJava(json: io.circe.Json): AnyRef = {
    if (json.isString) json.asString.get
    else if (json.isBoolean) java.lang.Boolean.valueOf(json.asBoolean.get)
    else if (json.isNumber) {
      val num = json.asNumber.get
      num.toInt match {
        case Some(i) => java.lang.Integer.valueOf(i)
        case None => java.lang.Double.valueOf(num.toDouble)
      }
    } else if (json.isArray) {
      json.asArray.get.map(jsonToJava).asJava
    } else if (json.isObject) {
      json.asObject.get.toMap.map { case (k, v) => k -> jsonToJava(v) }.asJava
    } else {
      null
    }
  }
}

object StdioMcpRegistry {
  def resource(mcpServers: McpServers): Resource[IO, StdioMcpRegistry] = {
    val acquireAll = IO {
      var startedClients = List.empty[McpSyncClient]
      var toolMap = Map.empty[String, McpSyncClient]
      var clientMap = Map.empty[String, McpSyncClient]
      var toolList = List.empty[com.tark.domain.tool.McpToolDefinition]

      try {
        mcpServers.mcpServers.foreach { case (serverName, serverConfig) =>
          try {
            serverConfig match {
              case McpServer.Command(command, args, env) =>
                println(s"[INFO] Starting MCP server process: '$serverName' ($command)")
                val params = ServerParameters.builder(command)
                  .args(args.asJava)
                  .env(env.asJava)
                  .build()

                val mapper = io.modelcontextprotocol.json.McpJsonDefaults.getMapper
                val transport = new StdioClientTransport(params, mapper)
                val logPath = java.nio.file.Paths.get(".tark", "mcp.log")
                transport.setStdErrorHandler { line =>
                  val truncatedLine = if (line.length > 300) line.take(300) + "..." else line
                  try {
                    java.nio.file.Files.writeString(
                      logPath,
                      s"[${java.time.Instant.now()}] [$serverName] $truncatedLine\n",
                      java.nio.file.StandardOpenOption.CREATE,
                      java.nio.file.StandardOpenOption.APPEND
                    )
                  } catch {
                    case _: Throwable => ()
                  }
                }
                val client = JavaMcpClient.sync(transport)
                  .requestTimeout(Duration.ofSeconds(10))
                  .build()

                try {
                  client.initialize()
                  val toolsResult = client.listTools()
                  toolsResult.tools().asScala.foreach { javaTool =>
                    val schemaString = mapper.writeValueAsString(javaTool.inputSchema())
                    val schemaJson = io.circe.parser.parse(schemaString).getOrElse(io.circe.Json.obj())
                    val mcpToolDef = com.tark.domain.tool.McpToolDefinition(
                      name = javaTool.name(),
                      description = javaTool.description(),
                      parameters = com.tark.domain.tool.OpenAIFunctionParams.Custom(schemaJson)
                    )
                    toolMap = toolMap + (javaTool.name() -> client)
                    toolList = mcpToolDef :: toolList
                  }

                  startedClients = client :: startedClients
                  clientMap = clientMap + (serverName -> client)
                } catch {
                  case initError: Throwable =>
                    try { client.closeGracefully() } catch { case _: Throwable => () }
                    throw initError
                }

              case McpServer.Remote(transportType, url) =>
                val lowerType = transportType.toLowerCase
                if (lowerType == "http") {
                  println(s"[INFO] Connecting to remote HTTP MCP server: '$serverName' ($url)")
                  val mapper = io.modelcontextprotocol.json.McpJsonDefaults.getMapper()
                  val transport = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(url).build()
                  val client = JavaMcpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(15))
                    .build()

                  try {
                    client.initialize()
                    val toolsResult = client.listTools()
                    toolsResult.tools().asScala.foreach { javaTool =>
                      val schemaString = mapper.writeValueAsString(javaTool.inputSchema())
                      val schemaJson = io.circe.parser.parse(schemaString).getOrElse(io.circe.Json.obj())
                      val mcpToolDef = com.tark.domain.tool.McpToolDefinition(
                        name = javaTool.name(),
                        description = javaTool.description(),
                        parameters = com.tark.domain.tool.OpenAIFunctionParams.Custom(schemaJson)
                      )
                      toolMap = toolMap + (javaTool.name() -> client)
                      toolList = mcpToolDef :: toolList
                    }

                    startedClients = client :: startedClients
                    clientMap = clientMap + (serverName -> client)
                  } catch {
                    case initError: Throwable =>
                      try { client.closeGracefully() } catch { case _: Throwable => () }
                      throw initError
                  }
                } else {
                  println(s"[WARN] Unsupported remote transport type '$transportType' for server '$serverName'")
                }
            }
          } catch {
            case ex: Throwable =>
              System.err.println(s"[WARN] Failed to initialize MCP server '$serverName': ${ex.getMessage}")
          }
        }
        (clientMap, toolMap, toolList, startedClients)
      } catch {
        case ex: Throwable =>
          startedClients.foreach { c =>
            try { c.closeGracefully() } catch { case _: Throwable => () }
          }
          throw ex
      }
    }

    Resource.make(acquireAll) { case (_, _, _, startedClients) =>
      IO {
        println("[INFO] Closing all MCP server process connections...")
        startedClients.foreach { c =>
          try { c.closeGracefully() } catch { case _: Throwable => () }
        }
      }
    }.map { case (clientMap, toolMap, toolList, _) =>
      new StdioMcpRegistry(clientMap, toolMap, toolList)
    }
  }
}
