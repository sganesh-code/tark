package com.tark.ports.inbound.tool

import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.context.{Context, Session}
import com.tark.domain.tool.{ToolCall, ToolCallFunction}
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.tool.CommandExecutor
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.shared.ui.ChatState
import io.circe.syntax.*

import java.nio.file.Path

sealed trait CommandType
case object SLASH_COMMAND extends CommandType
case object HARNESS_COMMAND extends CommandType
case object REACT_COMMAND extends CommandType

object CommandClassifier {
  def classify(input: String): CommandType = {
    val trimmed = input.trim
    if (trimmed == "/run" || trimmed.startsWith("/run ")) {
      HARNESS_COMMAND
    } else if (trimmed.startsWith("/")) {
      SLASH_COMMAND
    } else {
      REACT_COMMAND
    }
  }
}

trait SlashCommand[F[_]] {
  def name: String
  def execute(state: ChatState, session: Session): F[Option[(ChatState, Session)]]
}

object SlashCommand {
  class ExitCommand[F[_]: cats.MonadThrow](using summarizer: EpisodicMemorySummarizer[F], sink: Sink[F, Context, Path], clock: Clock[F]) extends SlashCommand[F] {
    override val name: String = "/exit"
    override def execute(state: ChatState, session: Session): F[Option[(ChatState, Session)]] = {
      if (session.context.history.nonEmpty) {
        SessionMemoryTransitions
          .summarizeAndPersist(
            session = session,
            fallbackReason = "Exited with summarization error",
            transform = identity,
            persistWhenHistoryEmpty = false
          )
          .as(None)
      } else {
        cats.MonadThrow[F].pure(None)
      }
    }
  }

  class ClearCommand[F[_]: cats.MonadThrow](using summarizer: EpisodicMemorySummarizer[F], sink: Sink[F, Context, Path], clock: Clock[F]) extends SlashCommand[F] {
    override val name: String = "/clear"
    override def execute(state: ChatState, session: Session): F[Option[(ChatState, Session)]] = {
      SessionMemoryTransitions
        .summarizeAndPersist(
          session = session,
          fallbackReason = "Cleared with summarization error",
          transform = _.copy(history = List.empty),
          persistWhenHistoryEmpty = true
        )
        .map { updatedContext =>
          val nextSession = session.copy(context = updatedContext)
          val nextState = ChatState(history = Vector.empty, prompt = "")
          Some((nextState, nextSession))
        }
    }
  }

  class MemoryCommand[F[_]: cats.Applicative] extends SlashCommand[F] {
    override val name: String = "/memory"
    override def execute(state: ChatState, session: Session): F[Option[(ChatState, Session)]] = {
      val mem = session.context.memory
      val sb = new java.lang.StringBuilder()
      sb.append("[Memory Layers Status]\n")
      
      sb.append("  * Working Memory: ")
      mem.working match {
        case Some(w) =>
          sb.append(s"Active (Goal = '${w.goal}', Done = ${w.done})\n")
          if (w.constraints.nonEmpty) sb.append(s"    - Constraints: ${w.constraints.mkString(", ")}\n")
          if (w.plan.nonEmpty) sb.append(s"    - Plan Steps: ${w.plan.size} steps\n")
        case None =>
          sb.append("Inactive\n")
      }
      
      sb.append(s"  * Episodic Memory: ${mem.episodic.episodes.size} episodes\n")
      mem.episodic.episodes.zipWithIndex.foreach { case (ep, idx) =>
        sb.append(s"    [$idx] Session '${ep.sessionId}': ${ep.summary}\n")
        if (ep.keyTakeaways.nonEmpty) {
          sb.append(s"         Takeaways: ${ep.keyTakeaways.mkString(", ")}\n")
        }
      }
      
      sb.append(s"  * Procedural Memory: ${session.context.tools.size} tools, ${mem.procedural.skills.size} skills\n")
      if (session.context.tools.nonEmpty) {
        sb.append(s"    - Registered Tools: ${session.context.tools.map(_.function.name).mkString(", ")}\n")
      }
      mem.procedural.skills.foreach { skill =>
        sb.append(s"    - Skill '${skill.name}': ${skill.description}\n")
      }

      val nextState = ChatTransitions.userAndSystem(state, sb.toString.trim)
      cats.Applicative[F].pure(Some((nextState, session)))
    }
  }

  class RunCommand[F[_]: cats.effect.Sync](using clock: Clock[F], commandExecutor: CommandExecutor[F]) extends SlashCommand[F] {
    override val name: String = "/run"
    override def execute(state: ChatState, session: Session): F[Option[(ChatState, Session)]] = {
      val rawCommand = state.prompt.trim.stripPrefix("/run").trim
      if (rawCommand.isEmpty) {
        val nextState = ChatTransitions.userAndSystem(state, "Error: No command provided to run. Usage: /run <command>")
        cats.Applicative[F].pure(Some((nextState, session)))
      } else {
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
              
              interaction = com.tark.domain.Interaction(
                id = s"interaction_$now",
                input = s"/run $rawCommand",
                output = result.content,
                timestamp = now,
                toolName = "command_executor"
              )
              updatedContext = session.context.copy(history = session.context.history :+ interaction)
              
              nextState = ChatTransitions.userAndSystem(state, s"[EXECUTING] -> $rawCommand\n${result.content}")
            } yield Some((nextState, session.copy(context = updatedContext)))
            
          case None =>
            val nextState = ChatTransitions.userAndSystem(state, "Error: command_executor tool is not registered.")
            cats.Applicative[F].pure(Some((nextState, session)))
        }
      }
    }
  }

  class HelpCommand[F[_]: cats.Applicative] extends SlashCommand[F] {
    override val name: String = "/help"
    override def execute(state: ChatState, session: Session): F[Option[(ChatState, Session)]] = {
      val helpMsg = "Available commands: /help, /clear, /memory, /run <command>, /exit"
      val nextState = ChatTransitions.userAndSystem(state, helpMsg)
      cats.Applicative[F].pure(Some((nextState, session)))
    }
  }

  class UnknownCommand[F[_]: cats.Applicative](commandName: String) extends SlashCommand[F] {
    override val name: String = commandName
    override def execute(state: ChatState, session: Session): F[Option[(ChatState, Session)]] = {
      val nextState = ChatTransitions.userAndSystem(state, s"Unknown command: ${state.prompt.trim}")
      cats.Applicative[F].pure(Some((nextState, session)))
    }
  }

  given defaultCommands[F[_]: cats.effect.Sync](using
    summarizer: EpisodicMemorySummarizer[F],
    sink: Sink[F, Context, Path],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F]
  ): List[SlashCommand[F]] = List(
    new ExitCommand[F],
    new ClearCommand[F],
    new MemoryCommand[F],
    new HelpCommand[F],
    new RunCommand[F]
  )
}
