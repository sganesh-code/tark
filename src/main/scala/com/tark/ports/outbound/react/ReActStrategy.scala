package com.tark.ports.outbound.react

import com.tark.domain.tool.Tool

trait ReActStrategy[F[_], Msg, Req, Res] {
  def prepareRequest(
    model: String,
    messages: List[Msg],
    tools: List[Tool],
    systemPrompt: String
  ): Req

  def parseResponse(
    rawContent: String,
    response: Res,
    tools: List[Tool]
  ): F[Either[String, ReActResponse]]
}
