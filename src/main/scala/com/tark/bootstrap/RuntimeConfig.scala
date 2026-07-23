package com.tark.bootstrap

import cats.effect.IO
import com.tark.domain.Config
import com.tark.domain.mcp.McpServers

case class RuntimeConfig(
  config: Config,
  forceBuild: Boolean,
  mcpServers: McpServers = McpServers(Map.empty)
)

object RuntimeConfig {
  val DefaultForceBuild = false

  def fromEnvironment: IO[RuntimeConfig] =
    for {
      env <- IO(sys.env)
      base = fromEnv(env)
      mcp <- loadMcpServers
    } yield base.copy(mcpServers = mcp)

  private def loadMcpServers: IO[McpServers] = IO {
    import io.circe.parser.decode
    import java.nio.file.{Files, Paths}
    val path = Paths.get(".tark", "settings.json")
    if (Files.exists(path)) {
      val content = Files.readString(path)
      decode[McpServers](content) match {
        case Right(servers) =>
          if (servers.mcpServers.nonEmpty) {
            println(s"[INFO] Loaded MCP server registrations: ${servers.mcpServers.keys.mkString(", ")}")
          }
          servers
        case Left(error) =>
          System.err.println(s"[WARN] Failed to parse .tark/settings.json: $error")
          McpServers(Map.empty)
      }
    } else {
      McpServers(Map.empty)
    }
  }.handleErrorWith { error =>
    IO {
      System.err.println(s"[WARN] Error reading .tark/settings.json: ${error.getMessage}")
      McpServers(Map.empty)
    }
  }

  def fromEnv(env: Map[String, String]): RuntimeConfig = {
    val config = Config(
      modelId = env.getOrElse("TARK_OLLAMA_MODEL", Config.DefaultModelId),
      maxTokens = env.get("TARK_MAX_TOKENS").flatMap(_.toIntOption).getOrElse(Config.DefaultMaxTokens),
      baseUrl = env.getOrElse("TARK_OLLAMA_URL", Config.DefaultBaseUrl),
      sandboxImageName = env.getOrElse("TARK_SANDBOX_IMAGE", Config.DefaultSandboxImageName),
      panelWidth = env.get("TARK_PANEL_WIDTH").flatMap(_.toIntOption).getOrElse(80),
      panelBorder = env.getOrElse("TARK_PANEL_BORDER", "rounded"),
      contextWindowSize = env.get("TARK_CONTEXT_WINDOW_SIZE").flatMap(_.toIntOption).getOrElse(Config.DefaultContextWindowSize),
      enableDistillation = env.get("TARK_ENABLE_DISTILLATION").flatMap(_.toBooleanOption).getOrElse(Config.DefaultEnableDistillation),
      distillationThreshold = env.get("TARK_DISTILLATION_THRESHOLD").flatMap(_.toIntOption).getOrElse(Config.DefaultDistillationThreshold),
      frontendType = env.getOrElse("TARK_FRONTEND", Config.DefaultFrontendType)
    )

    RuntimeConfig(
      config = config,
      forceBuild = env.get("TARK_FORCE_BUILD").flatMap(_.toBooleanOption).getOrElse(DefaultForceBuild)
    )
  }
}
