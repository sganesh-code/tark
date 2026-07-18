package com.tark.bootstrap

import cats.effect.*
import com.tark.adapters.context.DefaultSessionProvider.given
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
      completionRef <- Resource.eval(Ref.of[IO, List[String]](DefaultAgentBackend.DefaultCompletions))
      terminalAndReader <- JLineFrontend.terminalAndReader(completionRef)
    } yield (client, session, completionRef, terminalAndReader)

    appResource.use { case (llmClient, session, completionRef, (terminal, lineReader)) =>
      given LlmClient[IO] = llmClient
      given StreamingLlmClient[IO] = llmClient.streaming.getOrElse(StreamingLlmClient.fromBuffered(llmClient))

      for {
        backend <- DefaultAgentBackend.create[IO](session)
        _ <- backend.registerCompletions(words => completionRef.set(words))
        _ <- JLineFrontend.resource(terminal, lineReader, backend).use(_.loop)
      } yield ()
    }
  }
}
