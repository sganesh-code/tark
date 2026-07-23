package com.tark.application.backend

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.tark.domain.Prompt
import com.tark.domain.tool.{OpenAIUsage, ToolCall}
import com.tark.ports.outbound.backend.*
import com.tark.ui.AgentAction
import munit.FunSuite

class StreamingResponseHandlerSpec extends FunSuite {
  test("StreamingResponseHandler collects streaming response content events successfully") {
    val usageRef = Ref.unsafe[IO, OpenAIUsage](OpenAIUsage(0, 0, 0))
    
    val streamingClient = new StreamingLlmClient[IO] {
      override def chatStream(prompt: Prompt): fs2.Stream[IO, LlmStreamEvent] =
        fs2.Stream(
          LlmStreamEvent.ContentDelta("Hello "),
          LlmStreamEvent.ContentDelta("World!"),
          LlmStreamEvent.Usage(OpenAIUsage(5, 5, 10)),
          LlmStreamEvent.Completed(Some(LLMResponse("Hello World!", List.empty, OpenAIUsage(5, 5, 10))))
        )
    }

    val llmClient = new LlmClient[IO] {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("should not be called"))
    }

    val handler = new StreamingResponseHandler[IO](streamingClient, llmClient, usageRef, com.tark.domain.Config.default)
    val responseRef = Ref.unsafe[IO, Option[LLMResponse[ToolCall]]](None)

    val prompt = Prompt(List.empty, List.empty)
    val actions = handler.collectStreamingResponse(prompt, responseRef).compile.toList.unsafeRunSync()

    assertEquals(
      actions,
      List(
        AgentAction.AssistantDelta("Hello "),
        AgentAction.AssistantDelta("World!"),
        AgentAction.AssistantEnd(),
        AgentAction.StatusUpdate("Context Window: 5/32768 tokens (0.0%) | Total Usage: Prompt 5 | Completion 5 | Total 10")
      )
    )

    val response = responseRef.get.unsafeRunSync()
    assert(response.isDefined)
    assertEquals(response.get.content, "Hello World!")
    assertEquals(response.get.usage, OpenAIUsage(5, 5, 10))
  }
}
