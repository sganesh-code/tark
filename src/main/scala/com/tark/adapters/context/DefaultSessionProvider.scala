package com.tark.adapters.context

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.tark.adapters.sandbox.docker.{DockerSandbox, DockerSandboxLifecycle}
import com.tark.adapters.tool.command.CommandTool
import com.tark.domain.Config
import com.tark.domain.context.{Context, Session}
import com.tark.domain.memory
import com.tark.domain.memory.Memory
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.outbound.context.{SessionProvider, SessionRepository}

import java.nio.file.Path

case class SessionProviderSettings(config: Config, forceBuild: Boolean)

object SessionProviderSettings {
  given default: SessionProviderSettings =
    SessionProviderSettings(Config.default, forceBuild = false)
}

object DefaultSessionProvider {
  given default(using
    settings: SessionProviderSettings,
    sink: Sink[IO, Context, Path],
    sessionRepository: SessionRepository[IO]
  ): SessionProvider[IO] with {
    override def createSession: Resource[IO, Session] = {
      val acquire: IO[Session] = for {
        now <- IO.realTime.map(_.toMillis)
        sessionId = s"session_$now"
        sessionPath = Path.of(s"target/sessions/$sessionId.md")
        
        config = settings.config
        
        sandbox = DockerSandbox(
          name = s"tark-sandbox-$sessionId",
          imageName = config.sandboxImageName,
          hostPath = Path.of(".")
        )
        
        _ <- DockerSandboxLifecycle.ensureImageExists(config.sandboxImageName, settings.forceBuild)
        
        _ <- IO.println("Starting Docker sandbox container...")
        _ <- DockerSandboxLifecycle.start(sandbox)
        
        existingMemory <- sessionRepository.loadLatestMemory(Path.of("target/sessions"))
        
        context = Context(List(CommandTool.definition), existingMemory, List.empty, Some(sandbox))
        session = Session(sessionId, context, sessionPath)
        
        _ <- sink.write(session.context, sessionPath)
      } yield session

      def release(session: Session): IO[Unit] = {
        session.context.sandbox match {
          case Some(sandbox: DockerSandbox) =>
            for {
              _ <- IO.println("\nStopping and cleaning up Docker sandbox container...")
              _ <- DockerSandboxLifecycle.stop(sandbox)
            } yield ()
          case _ => IO.unit
        }
      }

      Resource.make(acquire)(release)
    }
  }
}
