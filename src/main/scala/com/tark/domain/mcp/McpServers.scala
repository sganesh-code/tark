package com.tark.domain.mcp

import cats.Monad
import cats.effect.Resource
import com.tark.domain.Prompt


import io.circe.*
import io.circe.generic.semiauto.*

enum McpServer:
  case Command(command: String, args: List[String], env: Map[String, String])
  case Remote(`type`: String, url: String)

object McpServer {
  given Decoder[McpServer] = Decoder.instance { cursor =>
    cursor.get[String]("command").map { cmd =>
      val args = cursor.get[List[String]]("args").getOrElse(List.empty)
      val env = cursor.get[Map[String, String]]("env").getOrElse(Map.empty)
      McpServer.Command(cmd, args, env)
    }.orElse {
      for {
        t <- cursor.get[String]("type")
        url <- cursor.get[String]("url")
      } yield McpServer.Remote(t, url)
    }
  }

  given Encoder[McpServer] = Encoder.instance {
    case Command(cmd, args, env) =>
      Json.obj(
        "command" -> Json.fromString(cmd),
        "args" -> Json.arr(args.map(Json.fromString)*),
        "env" -> Json.obj(env.map { case (k, v) => k -> Json.fromString(v) }.toSeq*)
      )
    case Remote(t, url) =>
      Json.obj(
        "type" -> Json.fromString(t),
        "url" -> Json.fromString(url)
      )
  }
}

case class McpServers(mcpServers: Map[String, McpServer])

object McpServers {
  given Decoder[McpServers] = deriveDecoder
  given Encoder[McpServers] = deriveEncoder
}

case class McpToolInfo(
                   name: String,
                   description: String,
                   inputSchema: String
                   )

case class McpToolCall(
                      name: String,
                      arguments: Map[String, Any]
                      )

type McpToolResult = String

trait McpClient[F[_]]:
  def addTools(prompt: Prompt): F[Prompt]
 

trait McpTransport[F[_]: Monad]:
  def client(): Resource[F, McpClient[F]]  