package com.tark.application.backend

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.tark.application.time.Clock
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolResult, OpenAIUsage}
import com.tark.ports.outbound.backend.*
import com.tark.ports.outbound.tool.CommandExecutor
import munit.FunSuite

class ReActLoopEngineSpec extends FunSuite {
  test("ReActLoopEngine runs a simple conversation with final answer directly") {
    val usageRef = Ref.unsafe[IO, OpenAIUsage](OpenAIUsage(0, 0, 0))
    
    val streamingClient = new StreamingLlmClient[IO] {
      override def chatStream(prompt: Prompt): fs2.Stream[IO, LlmStreamEvent] =
        fs2.Stream(
          LlmStreamEvent.ContentDelta("Response answer"),
          LlmStreamEvent.Completed(Some(LLMResponse("Response answer", List.empty, OpenAIUsage(2, 2, 4))))
        )
    }

    val llmClient = new LlmClient[IO] {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("should not be called"))
    }

    val handler = new StreamingResponseHandler[IO](streamingClient, llmClient, usageRef)
    
    val executor = new CommandExecutor[IO] {
      override def definition = null
      override def execute(context: Context, toolCall: ToolCall) = IO.raiseError(new AssertionError("no tools"))
    }

    val clock = new Clock[IO] {
      override def realTimeMillis = IO.pure(12345L)
    }

    val engine = new ReActLoopEngine[IO](handler, executor, clock)
    val context = Context(List.empty, Memory(), List.empty)
    val messages = List(OpenAIMessage(role = "user", content = Some("Tell me a story")))
    val resultRef = Ref.unsafe[IO, Option[ConversationResult]](None)

    val tasksAndActions = engine
      .runConversation(context, messages, depth = 0, Vector.empty, resultRef)
      .evalMap(task => task.action.compile.toList.map(actions => task.description -> actions))
      .compile
      .toList
      .unsafeRunSync()

    assert(tasksAndActions.nonEmpty)
    assertEquals(tasksAndActions.head._1, Some("Waiting for assistant response"))
    assertEquals(tasksAndActions.last._1, Some("Finalizing assistant response"))

    val result = resultRef.get.unsafeRunSync()
    assert(result.isDefined)
    assertEquals(result.get.finalAnswer, Some("Response answer"))
    assertEquals(result.get.interactions.size, 1)
    assertEquals(result.get.interactions.head.output, "Response answer")
  }
}
