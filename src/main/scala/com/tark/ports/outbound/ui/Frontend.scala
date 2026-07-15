package com.tark.ports.outbound.ui

import com.tark.domain.context.Session
import com.tark.ports.shared.ui.ChatState

trait Frontend[F[_]] {
  def redraw(state: ChatState): F[Unit]
  def loop(state: ChatState, session: Session): F[Unit]
}
