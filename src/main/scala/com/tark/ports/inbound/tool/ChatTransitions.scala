package com.tark.ports.inbound.tool

import com.tark.ports.shared.ui.{ChatState, Message}

object ChatTransitions {
  def appendSystem(state: ChatState, message: String): ChatState =
    state.copy(history = state.history :+ Message.System(message))

  def userAndSystem(state: ChatState, systemMessage: String): ChatState =
    state.copy(
      history = (state.history :+ Message.User(state.prompt)) :+ Message.System(systemMessage),
      prompt = ""
    )

  def clearPrompt(state: ChatState): ChatState =
    state.copy(prompt = "")
}
