package com.tark.bootstrap

import cats.effect.*
import com.tark.adapters.context.DefaultSessionProvider.given
import com.tark.adapters.context.FileSessionRepository.given
import com.tark.adapters.context.SessionProviderSettings
import com.tark.adapters.inbound.terminal.jline.JLineFrontend
import com.tark.adapters.tool.command.CommandTool.given
import com.tark.application.backend.DefaultAgentBackend
import com.tark.application.instances.all.given
import com.tark.application.time.Clock.given
import com.tark.bootstrap.OllamaRuntime.given
import com.tark.domain.Config
import com.tark.ports.outbound.backend.{BackendProvider, LlmClient, StreamingLlmClient}
import com.tark.ports.outbound.context.SessionProvider

object TarkApp {
  def run: IO[Unit] =
    RuntimeConfig.fromEnvironment.flatMap(run)

  def run(runtimeConfig: RuntimeConfig): IO[Unit] = {
    given RuntimeConfig = runtimeConfig
    given Config = runtimeConfig.config
    given SessionProviderSettings = SessionProviderSettings(runtimeConfig.config, runtimeConfig.forceBuild)

    val appResource = for {
      client <- summon[BackendProvider[IO]].getClient
      session <- summon[SessionProvider[IO]].createSession
      mcpRegistry <- com.tark.adapters.mcp.StdioMcpRegistry.resource(runtimeConfig.mcpServers)
      completionRef <- Resource.eval(Ref.of[IO, List[String]](com.tark.application.backend.SlashCommandBackend.DefaultCompletions))
      terminalAndReader <- JLineFrontend.terminalAndReader(completionRef)
    } yield (client, session, mcpRegistry, completionRef, terminalAndReader)

    appResource.use { case (llmClient, session, mcpRegistry, completionRef, (terminal, lineReader)) =>
      given LlmClient[IO] = llmClient
      given StreamingLlmClient[IO] = llmClient.streaming.getOrElse(StreamingLlmClient.fromBuffered(llmClient))
      given com.tark.ports.outbound.mcp.McpRegistry[IO] = mcpRegistry

      for {
        mcpTools <- mcpRegistry.getTools
        _ <- if (mcpTools.nonEmpty) IO(println(s"[INFO] Registering ${mcpTools.size} MCP tools to session context: ${mcpTools.map(_.function.name).mkString(", ")}")) else IO.unit
        mcpSession = session.copy(
          context = session.context.copy(
            tools = session.context.tools ++ mcpTools
          )
        )
        backend <- DefaultAgentBackend.create[IO](mcpSession)
        _ <- backend.registerCompletions(words => completionRef.set(words))
        _ <- JLineFrontend.resource(terminal, lineReader, backend).use(_.loop)
      } yield ()
    }
  }
}
