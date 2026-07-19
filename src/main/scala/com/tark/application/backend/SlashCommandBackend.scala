package com.tark.application.backend

import cats.effect.Ref
import cats.effect.kernel.Sync
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.Interaction
import com.tark.domain.context.{Context, Session}
import com.tark.domain.tool.{ToolCall, ToolCallFunction}
import com.tark.ports.AgentBackend
import com.tark.ports.inbound.tool.SessionMemoryTransitions
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.tool.CommandExecutor
import com.tark.ports.shared.serialization.Sink
import com.tark.ui.{AgentAction, AgentTask, MemoryPresenter}
import fs2.Stream
import io.circe.syntax.*

import java.nio.file.Path

class SlashCommandBackend[F[_]: Sync](
                                      emitActions: (actions: Seq[AgentAction[F]]) => Stream[F, AgentTask[F]],
                                      sessionRef: Ref[F, Session],
                                      commandExecutor: CommandExecutor[F],
                                      clock: Clock[F],
                                      updateCompletionsRef: Ref[F, List[String] => F[Unit]]
                                      )(using summarizer: EpisodicMemorySummarizer[F],
                                              sink: Sink[F, Context, Path]) extends AgentBackend[F]{

  override def registerCompletions(update: List[String] => F[Unit]): F[Unit] =
    updateCompletionsRef.set(update) >> update(SlashCommandBackend.DefaultCompletions)

  override def handleInput(command: String): fs2.Stream[F, AgentTask[F]] = {
    val name = command.split("\\s+", 2).headOption.getOrElse(command)
    name match {
      case "/help" =>
        emitActions(
          Seq(AgentAction.SystemMessage("Available commands: /help, /clear, /memory, /run <command>, /exit"))
        )

      case "/memory" =>
        Stream.emit(
          AgentTask(
            description = None,
            action = Stream.eval(sessionRef.get.map(session => AgentAction.SystemMessage(MemoryPresenter.renderMemory(session.context))): F[AgentAction[F]])
          )
        )

      case "/run" =>
        val rawCommand = command.stripPrefix("/run").trim
        Stream.emit(
          AgentTask(
            description = if rawCommand.nonEmpty then Some(s"Executing command: $rawCommand") else None,
            action = Stream.eval(runCommand(rawCommand)).flatMap(actions => Stream.emits(actions))
          )
        )

      case "/clear" =>
        Stream.emit(
          AgentTask(
            description = Some("Summarizing session history and clearing"),
            action = Stream.eval(clearSession()).flatMap(actions => Stream.emits(actions))
          )
        )

      case "/exit" =>
        Stream.emit(
          AgentTask(
            description = Some("Summarizing session history"),
            action = Stream.eval(exitSession()).flatMap(actions => Stream.emits(actions))
          )
        )

      case _ =>
        emitActions(Seq(AgentAction.SystemMessage(s"Unknown command: $command. Type /help for assistance.")))
    }
  }

  private def runCommand(rawCommand: String): F[Vector[AgentAction[F]]] =
    if rawCommand.isEmpty then
      Sync[F].pure(Vector(AgentAction.SystemMessage("Error: No command provided to run. Usage: /run <command>")))
    else
      sessionRef.get.flatMap { session =>
        session.context.tools.find(_.function.name == "command_executor") match {
          case Some(_) =>
            for {
              now <- clock.realTimeMillis
              toolCall = ToolCall(
                id = s"exec_$now",
                `type` = "function",
                function = ToolCallFunction("command_executor", Map("command" -> rawCommand).asJson.noSpaces)
              )
              result <- commandExecutor.execute(session.context, toolCall)
              interaction = Interaction(
                id = s"interaction_$now",
                input = s"/run $rawCommand",
                output = result.content,
                timestamp = now,
                toolName = "command_executor"
              )
              updatedContext = session.context.copy(history = session.context.history :+ interaction)
              updatedSession = session.copy(context = updatedContext)
              _ <- sink.write(updatedContext, session.sessionPath)
              _ <- sessionRef.set(updatedSession)
            } yield Vector(AgentAction.Log(s"[EXECUTING] -> $rawCommand"), AgentAction.SystemMessage(result.content))

          case None =>
            Sync[F].pure(Vector(AgentAction.SystemMessage("Error: command_executor tool is not registered.")))
        }
      }

  private def clearSession(): F[Vector[AgentAction[F]]] =
    sessionRef.get.flatMap { session =>
      SessionMemoryTransitions
        .summarizeAndPersist(
          session = session,
          fallbackReason = "Cleared with summarization error",
          transform = _.copy(history = List.empty),
          persistWhenHistoryEmpty = true
        )
        .flatMap { updatedContext =>
          sessionRef.set(session.copy(context = updatedContext)).as {
            Vector(
              AgentAction.ClearScreen(),
              AgentAction.SystemMessage("Session cleared.")
            )
          }
        }
    }

  private def exitSession(): F[Vector[AgentAction[F]]] =
    sessionRef.get.flatMap { session =>
      val persist =
        if session.context.history.nonEmpty then
          SessionMemoryTransitions.summarizeAndPersist(
            session = session,
            fallbackReason = "Exited with summarization error",
            transform = identity,
            persistWhenHistoryEmpty = false
          )
        else Sync[F].pure(session.context)

      persist.flatMap(context => sessionRef.set(session.copy(context = context))).as(Vector(AgentAction.Exit()))
    }
}

object SlashCommandBackend {
  val DefaultCompletions: List[String] = List("/help", "/clear", "/memory", "/run", "/exit")
}
