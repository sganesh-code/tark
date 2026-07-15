package com.tark.ports.outbound.backend

import com.tark.application.instances.all.given

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.Interaction
import com.tark.domain.tool.{Tool, ToolCallRequest}
import com.tark.ports.outbound.react.{ReActLlmClient, ReActResponse, ReActToolPolicy}
import munit.FunSuite

class BackendPipelineSpec extends FunSuite {

  case class FakeMessage(role: String, content: String)
  case class FakeRequest(model: String, messages: List[FakeMessage], format: Option[String])
  case class FakeResponse(toolCalls: Either[String, List[ToolCallRequest]])

  private val interaction = Interaction(
    id = "1",
    input = "previous user",
    output = "previous assistant",
    timestamp = 1L,
    toolName = "llm_completion"
  )

  given ToMessages[SystemPrompt, FakeMessage] with {
    override def toMessages(value: SystemPrompt): List[FakeMessage] =
      List(FakeMessage("system", value.content))
  }

  given ToMessages[UserPrompt, FakeMessage] with {
    override def toMessages(value: UserPrompt): List[FakeMessage] =
      List(FakeMessage("user", value.content))
  }

  given ToMessages[Interaction, FakeMessage] with {
    override def toMessages(value: Interaction): List[FakeMessage] =
      List(
        FakeMessage("history-user", value.input),
        FakeMessage("history-assistant", value.output)
      )
  }

  private def fakeRequestCreator: LlmRequestCreator[FakeMessage, FakeRequest] =
    new LlmRequestCreator[FakeMessage, FakeRequest] {
      override def createRequest(modelName: String, messages: List[FakeMessage], format: Option[String]): FakeRequest =
        FakeRequest(modelName, messages, format)
    }

  private def fakeExecutor(
    result: Either[String, FakeResponse],
    capture: FakeRequest => Unit = _ => ()
  ): LlmExecutor[IO, FakeRequest, FakeResponse] =
    new LlmExecutor[IO, FakeRequest, FakeResponse] {
      override def execute(request: FakeRequest): IO[Either[String, FakeResponse]] = IO {
        capture(request)
        result
      }
    }

  private def fakeParser(
    parse: FakeResponse => Either[String, List[ToolCallRequest]]
  ): LlmResponseParser[FakeResponse] =
    new LlmResponseParser[FakeResponse] {
      override def parseResponse(response: FakeResponse): Either[String, List[ToolCallRequest]] =
        parse(response)
    }

  test("LlmPipeline: derives LlmClient and preserves system-history-user message ordering") {
    var capturedRequest: Option[FakeRequest] = None
    val calls = List(
      ToolCallRequest("first_tool", Map("a" -> "1")),
      ToolCallRequest("second_tool", Map("b" -> "2"))
    )

    given LlmRequestCreator[FakeMessage, FakeRequest] = fakeRequestCreator
    given LlmExecutor[IO, FakeRequest, FakeResponse] =
      fakeExecutor(Right(FakeResponse(Right(calls))), request => capturedRequest = Some(request))
    given LlmResponseParser[FakeResponse] = fakeParser(_.toolCalls)

    val client = LlmPipeline.client[IO, FakeMessage, FakeRequest, FakeResponse]("fake-model", Some("json"))
    val result = client.getCompletion("current user", List(interaction), "system prompt", List.empty).unsafeRunSync()

    assertEquals(result, Right(calls))
    assertEquals(
      capturedRequest.map(_.messages),
      Some(List(
        FakeMessage("system", "system prompt"),
        FakeMessage("history-user", "previous user"),
        FakeMessage("history-assistant", "previous assistant"),
        FakeMessage("user", "current user")
      ))
    )
    assertEquals(capturedRequest.map(_.model), Some("fake-model"))
    assertEquals(capturedRequest.flatMap(_.format), Some("json"))
  }

  test("LlmPipeline: preserves executor errors and does not invoke parser") {
    var parserCalled = false

    given LlmRequestCreator[FakeMessage, FakeRequest] = fakeRequestCreator
    given LlmExecutor[IO, FakeRequest, FakeResponse] = fakeExecutor(Left("executor failed"))
    given LlmResponseParser[FakeResponse] = fakeParser { _ =>
        parserCalled = true
        Right(List.empty)
    }

    val client = LlmPipeline.client[IO, FakeMessage, FakeRequest, FakeResponse]("fake-model", None)
    val result = client.getCompletion("prompt", List.empty, "system", List.empty).unsafeRunSync()

    assertEquals(result, Left("executor failed"))
    assertEquals(parserCalled, false)
  }

  test("LlmPipeline: preserves parser errors and complete tool-call lists") {
    val calls = List(
      ToolCallRequest("first_tool", Map("a" -> "1")),
      ToolCallRequest("second_tool", Map("b" -> "2"))
    )

    given LlmRequestCreator[FakeMessage, FakeRequest] = fakeRequestCreator
    given LlmExecutor[IO, FakeRequest, FakeResponse] = fakeExecutor(Right(FakeResponse(Right(calls))))

    {
      given parserSuccess: LlmResponseParser[FakeResponse] = fakeParser(_.toolCalls)

      val client = LlmPipeline.client[IO, FakeMessage, FakeRequest, FakeResponse]("fake-model", None)
      val result: Either[String, List[ToolCallRequest]] =
        client.getCompletion("prompt", List.empty, "system", List.empty).unsafeRunSync()
      assertEquals(result, Right(calls))
    }

    {
      given parserFailure: LlmResponseParser[FakeResponse] = fakeParser(_ => Left("parser failed"))

      val parserFailureClient = LlmPipeline.client[IO, FakeMessage, FakeRequest, FakeResponse]("fake-model", None)
      val result: Either[String, List[ToolCallRequest]] =
        parserFailureClient.getCompletion("prompt", List.empty, "system", List.empty).unsafeRunSync()
      assertEquals(result, Left("parser failed"))
    }
  }

  test("ReActLlmClient default laws: text, empty tool list, and first-tool policy") {
    val first = ToolCallRequest("first_tool", Map("a" -> "1"))
    val second = ToolCallRequest("second_tool", Map("b" -> "2"))

    {
      given textClient: LlmClient[IO] = (_: String, _: List[Interaction], _: String, _: List[Tool]) =>
        IO.pure(Left("final text"))
      val textReAct = summon[ReActLlmClient[IO]]
        .getCompletion("prompt", List.empty, "system", List.empty)
        .unsafeRunSync()
      assertEquals(textReAct, Right(ReActResponse("final text", Left("final text"))))
    }

    {
      given emptyToolClient: LlmClient[IO] = (_: String, _: List[Interaction], _: String, _: List[Tool]) =>
        IO.pure(Right(List.empty))
      val emptyToolReAct = summon[ReActLlmClient[IO]]
        .getCompletion("prompt", List.empty, "system", List.empty)
        .unsafeRunSync()
      assertEquals(emptyToolReAct, Left("No tool calls returned"))
    }

    {
      given multiToolClient: LlmClient[IO] = (_: String, _: List[Interaction], _: String, _: List[Tool]) =>
        IO.pure(Right(List(first, second)))
      val multiToolReAct = summon[ReActLlmClient[IO]]
        .getCompletion("prompt", List.empty, "system", List.empty)
        .unsafeRunSync()
      assertEquals(multiToolReAct, Right(ReActResponse("Executing tool call...", Right(first))))
    }
  }

  test("ReActLlmClient: custom tool policy can select a non-default tool call") {
    val first = ToolCallRequest("first_tool", Map("a" -> "1"))
    val second = ToolCallRequest("second_tool", Map("b" -> "2"))
    val lastToolWins: ReActToolPolicy =
      calls => calls.lastOption.toRight("No tool calls returned")

    given llmClient: LlmClient[IO] = (_: String, _: List[Interaction], _: String, _: List[Tool]) =>
      IO.pure(Right(List(first, second)))

    val result = ReActLlmClient
      .fromLlmClient[IO](lastToolWins)
      .getCompletion("prompt", List.empty, "system", List.empty)
      .unsafeRunSync()

    assertEquals(result, Right(ReActResponse("Executing tool call...", Right(second))))
  }
}
