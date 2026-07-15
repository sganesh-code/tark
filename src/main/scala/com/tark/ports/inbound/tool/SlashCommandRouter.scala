package com.tark.ports.inbound.tool

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.context.Session
import com.tark.ports.shared.ui.{ChatState, Message}

trait SlashCommandRouter[F[_]] {
  def process(
    input: String,
    state: ChatState,
    session: Session,
    redraw: ChatState => F[Unit]
  ): F[Option[(ChatState, Session)]]
}

object SlashCommandRouter {
  given default[F[_]: Sync](using slashCommands: List[SlashCommand[F]]): SlashCommandRouter[F] with {
    override def process(
      input: String,
      state: ChatState,
      session: Session,
      redraw: ChatState => F[Unit]
    ): F[Option[(ChatState, Session)]] = {
      val trimmedInput = input.trim
      val cmdName = trimmedInput.split(" ").head
      slashCommands.find(_.name == cmdName) match {
        case Some(cmd) =>
          val preCommandState = if (cmdName == "/exit") {
            state.copy(history = state.history :+ Message.User("/exit") :+ Message.System("Summarizing session history and exiting..."), prompt = "")
          } else if (cmdName == "/clear") {
            state.copy(history = state.history :+ Message.User("/clear") :+ Message.System("Summarizing session history and clearing..."), prompt = "")
          } else {
            state
          }
          for {
            _ <- if (cmdName == "/exit" || cmdName == "/clear") redraw(preCommandState) else Sync[F].unit
            res <- cmd.execute(preCommandState, session)
          } yield res
        case None =>
          new SlashCommand.UnknownCommand[F](cmdName).execute(state, session)
      }
    }
  }
}
