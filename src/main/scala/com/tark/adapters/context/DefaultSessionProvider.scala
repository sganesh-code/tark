package com.tark.adapters.context

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.tark.adapters.tool.command.CommandTool
import com.tark.domain.Config
import com.tark.domain.context.{Context, Session}
import com.tark.domain.memory
import com.tark.domain.memory.Memory
import com.tark.ports.outbound.sandbox.SandboxManager
import com.tark.adapters.sandbox.docker.DockerSandbox
import com.tark.ports.shared.tool.ToolRegistry
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.outbound.context.SessionProvider

import java.nio.file.Path

case class SessionProviderSettings(config: Config, forceBuild: Boolean)

object SessionProviderSettings {
  given default: SessionProviderSettings =
    SessionProviderSettings(Config.default, forceBuild = false)
}

object DefaultSessionProvider {
  given default(using
    settings: SessionProviderSettings,
    sandboxManager: SandboxManager[IO, DockerSandbox],
    toolRegistry: ToolRegistry[Context],
    sink: Sink[IO, Context, Path]
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
        
        imageExists <- IO.blocking {
          try {
            scala.sys.process.Process(Seq("docker", "image", "inspect", config.sandboxImageName)).! == 0
          } catch {
            case _: Exception => false
          }
        }
        
        _ <- if (!imageExists || settings.forceBuild) {
          IO(println(s"Building Docker sandbox image ${config.sandboxImageName}...")) *>
          IO.blocking {
            try {
              scala.sys.process.Process(Seq("docker", "build", "-t", config.sandboxImageName, ".")).!!
            } catch {
              case e: Exception => println(s"Warning: Docker build skipped/failed: ${e.getMessage}")
            }
          }
        } else {
          IO(println(s"Docker sandbox image ${config.sandboxImageName} already exists. Skipping build."))
        }
        
        _ <- IO(println("Starting Docker sandbox container..."))
        _ <- sandboxManager.start(sandbox)
        
        existingMemory <- IO.blocking {
          import java.nio.file.Files
          val sessionsDir = Path.of("target/sessions")
          if (Files.exists(sessionsDir) && Files.isDirectory(sessionsDir)) {
            val files = Files.list(sessionsDir).toArray.map(_.asInstanceOf[Path])
              .filter(p => p.toString.endsWith(".md") && p.getFileName.toString.startsWith("session_"))
            if (files.nonEmpty) {
              val latestFile = files.maxBy(p => Files.getLastModifiedTime(p).toMillis)
              val content = Files.readString(latestFile)
              
              val jsonRegex = """(?s).*?<!-- MEMORY_JSON\n(.*?)\n-->.*?""".r
              content match {
                case jsonRegex(jsonStr) =>
                  import io.circe.parser.*
                  decode[Memory](jsonStr) match {
                    case Right(mem) =>
                      mem
                    case Left(_) =>
                      memory.Memory()
                  }
                case _ =>
                  memory.Memory()
              }
            } else {
              memory.Memory()
            }
          } else {
            memory.Memory()
          }
        }
        
        initialContext = Context(Map.empty, existingMemory, List.empty, Some(sandbox))
        contextWithTools = toolRegistry.register(initialContext, CommandTool.generic)
        session = Session(sessionId, contextWithTools, sessionPath)
        
        _ <- sink.write(session.context, sessionPath)
      } yield session

      def release(session: Session): IO[Unit] = {
        session.context.sandbox match {
          case Some(sandbox: DockerSandbox) =>
            for {
              _ <- IO(println("\nStopping and cleaning up Docker sandbox container..."))
              _ <- sandboxManager.stop(sandbox)
            } yield ()
          case _ => IO.unit
        }
      }

      Resource.make(acquire)(release)
    }
  }
}
