package com.tark.ports.inbound.tool

import com.tark.domain.context.Session
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.shared.ui.ChatState

trait InputProcessor[F[_]] {
  def process(
    input: String,
    state: ChatState,
    session: Session,
    redraw: ChatState => F[Unit]
  )(using llmClient: LlmClient[F]): F[Option[(ChatState, Session)]]
}
