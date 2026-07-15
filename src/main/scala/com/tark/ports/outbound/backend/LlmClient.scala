package com.tark.ports.outbound.backend

import cats.Functor
import cats.effect.IO
import cats.syntax.functor.*
import com.tark.domain.tool.{Tool, ToolCallRequest}
import com.tark.domain.Interaction

trait LlmClient[F[_]] {
  def getCompletion(
    prompt: String,
    history: List[Interaction],
    systemPrompt: String,
    tools: List[Tool] = List.empty
  ): F[Either[String, List[ToolCallRequest]]]
}

object LlmClient {
  /**
   * A local stub implementation that simulates an LLM response.
   */
  private val localStub: LlmClient[IO] = (prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]) => IO {
    Left(s"Stub Response to: '$prompt'")
  }

  /**
   * The default given instance of LlmClient[IO], using the localStub.
   * This can easily be overridden with an HTTP-based instance.
   */
  given LlmClient[IO] = localStub
}

// Prompt wrappers
case class SystemPrompt(content: String)
case class UserPrompt(content: String)

// Core Typeclasses for LLM Algebra

trait ToMessages[A, Msg] {
  def toMessages(value: A): List[Msg]
}

object ToMessages {
  given [A, Msg](using toMsg: ToMessages[A, Msg]): ToMessages[List[A], Msg] with {
    def toMessages(list: List[A]): List[Msg] = list.flatMap(toMsg.toMessages)
  }
}

trait LlmRequestCreator[Msg, Req] {
  def createRequest(modelName: String, messages: List[Msg], format: Option[String]): Req
}

trait LlmExecutor[F[_], Req, Res] {
  def execute(request: Req): F[Either[String, Res]]
}

trait LlmResponseParser[Res] {
  def parseResponse(response: Res): Either[String, List[ToolCallRequest]]
}

object LlmPipeline {
  def client[F[_]: Functor, Msg, Req, Res](
    modelName: String,
    format: Option[String]
  )(using
    systemMessages: ToMessages[SystemPrompt, Msg],
    userMessages: ToMessages[UserPrompt, Msg],
    historyMessages: ToMessages[Interaction, Msg],
    requestCreator: LlmRequestCreator[Msg, Req],
    executor: LlmExecutor[F, Req, Res],
    parser: LlmResponseParser[Res]
  ): LlmClient[F] =
    (prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]) => {
      val messages =
        systemMessages.toMessages(SystemPrompt(systemPrompt)) ++
          history.flatMap(historyMessages.toMessages) ++
          userMessages.toMessages(UserPrompt(prompt))

      val request = requestCreator.createRequest(modelName, messages, format)

      executor.execute(request).map {
        case Right(response) => parser.parseResponse(response)
        case Left(error) => Left(error)
      }
    }
}

// Extension methods to enable fluent, summon-free syntax

extension [A](value: A) {
  def toMessages[Msg](using tm: ToMessages[A, Msg]): List[Msg] =
    tm.toMessages(value)
}

extension [Msg](messages: List[Msg]) {
  def createRequest[Req](modelName: String, format: Option[String])(using creator: LlmRequestCreator[Msg, Req]): Req =
    creator.createRequest(modelName, messages, format)
}

extension [Req](request: Req) {
  def execute[F[_], Res]()(using executor: LlmExecutor[F, Req, Res]): F[Either[String, Res]] =
    executor.execute(request)
}

extension [Res](response: Res) {
  def parseResponse()(using parser: LlmResponseParser[Res]): Either[String, List[ToolCallRequest]] =
    parser.parseResponse(response)
}
