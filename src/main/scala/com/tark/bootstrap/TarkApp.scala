package com.tark.bootstrap

import cats.effect.*
import com.tark.adapters.context.DefaultSessionProvider.given
import com.tark.adapters.context.SessionProviderSettings
import com.tark.adapters.inbound.terminal.jline.JLineFrontend
import com.tark.adapters.tool.command.CommandTool.given
import com.tark.adapters.ui.ScreenWriterInstances.given
import com.tark.application.chat.DefaultInputProcessor.given
import com.tark.application.instances.all.given
import com.tark.application.time.Clock.given
import com.tark.bootstrap.OllamaRuntime.given
import com.tark.domain.Config
import com.tark.ports.inbound.tool.SlashCommand.given
import com.tark.ports.inbound.ui.KeyboardHandler.given
import com.tark.ports.outbound.backend.{BackendProvider, LlmClient}
import com.tark.ports.outbound.context.SessionProvider
import com.tark.ports.outbound.ui.Frontend
import com.tark.ports.shared.ui.ChatState
import org.jline.terminal.TerminalBuilder

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
      terminal <- Resource.make(
        IO.blocking {
          val t = TerminalBuilder.builder()
            .system(true)
            .build()
          t.enterRawMode()
          t
        }
      )(t => IO.blocking(t.close()))
    } yield (client, session, terminal)

    appResource.use { case (llmClient, session, terminal) =>
      given LlmClient[IO] = llmClient

      val frontend: Frontend[IO] = JLineFrontend(terminal)
      frontend.loop(ChatState(Vector.empty, ""), session)
    }
  }
}
