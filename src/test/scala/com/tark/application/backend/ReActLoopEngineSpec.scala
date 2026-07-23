package com.tark.application.backend

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.tark.application.time.Clock
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolCallFunction, ToolResult, OpenAIUsage}
import com.tark.ports.outbound.backend.*
import com.tark.domain.Prompt
import com.tark.ports.outbound.tool.{CommandExecutor, ToolCallExecutor}
import com.tark.ports.outbound.tool.ToolCallExecutor.given
import com.tark.ui.AgentAction
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

    val config = com.tark.domain.Config.default
    val handler = new StreamingResponseHandler[IO](streamingClient, llmClient, usageRef, config)
    
    given executor: CommandExecutor[IO] with {
      override def definition = null
      override def execute(context: Context, toolCall: ToolCall) = IO.raiseError(new AssertionError("no tools"))
    }

    val clock = new Clock[IO] {
      override def realTimeMillis = IO.pure(12345L)
    }

    val toolCallExecutor = summon[ToolCallExecutor[IO, com.tark.domain.tool.ToolDefinition]]
    val distiller = new ContextDistiller[IO](llmClient)
    val engine = new ReActLoopEngine[IO](handler, toolCallExecutor, clock, config)
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

  test("ReActLoopEngine executes interactive questionnaire tool call successfully") {
    val usageRef = Ref.unsafe[IO, OpenAIUsage](OpenAIUsage(0, 0, 0))
    val callCounter = Ref.unsafe[IO, Int](0)

    val streamingClient = new StreamingLlmClient[IO] {
      override def chatStream(prompt: Prompt): fs2.Stream[IO, LlmStreamEvent] = {
        fs2.Stream.eval(callCounter.getAndUpdate(_ + 1)).flatMap {
          case 0 =>
            fs2.Stream(
              LlmStreamEvent.ToolCallDelta(
                index = 0,
                id = Some("call_q"),
                callType = Some("function"),
                name = Some("questionnaire"),
                argumentsChunk = Some("""{"question": "Proceed?", "options": ["Yes", "No"]}""")
              ),
              LlmStreamEvent.Completed(
                Some(
                  LLMResponse(
                    content = "",
                    results = List(ToolCall("call_q", "function", ToolCallFunction("questionnaire", """{"question": "Proceed?", "options": ["Yes", "No"]}"""))),
                    usage = OpenAIUsage(10, 5, 15)
                  )
                )
              )
            )
          case _ =>
            fs2.Stream(
              LlmStreamEvent.ContentDelta("Final decision: Yes"),
              LlmStreamEvent.Completed(
                Some(
                  LLMResponse(
                    content = "Final decision: Yes",
                    results = List.empty,
                    usage = OpenAIUsage(20, 10, 30)
                  )
                )
              )
            )
        }
      }
    }

    val llmClient = new LlmClient[IO] {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("should not be called"))
    }

    val config = com.tark.domain.Config.default
    val handler = new StreamingResponseHandler[IO](streamingClient, llmClient, usageRef, config)

    given executor: CommandExecutor[IO] with {
      override def definition = null
      override def execute(context: Context, toolCall: ToolCall) = IO.raiseError(new AssertionError("command executor should not be called"))
    }

    val clock = new Clock[IO] {
      override def realTimeMillis = IO.pure(12345L)
    }

    val toolCallExecutor = summon[ToolCallExecutor[IO, com.tark.domain.tool.ToolDefinition]]
    val distiller = new ContextDistiller[IO](llmClient)
    val engine = new ReActLoopEngine[IO](handler, toolCallExecutor, clock, config)
    val context = Context(List(com.tark.domain.tool.ToolDefinition.Questionnaire), Memory(), List.empty)
    val messages = List(OpenAIMessage(role = "user", content = Some("Interactive prompt")))
    val resultRef = Ref.unsafe[IO, Option[ConversationResult]](None)

    // We compile and execute the tasks as they are produced in the stream.
    // We intercept the RequestChoice action of the questionnaire tool and select "Yes".
    var capturedChoicePrompt: Option[String] = None
    var capturedChoiceOptions: Option[List[String]] = None

    def executeActions(actionStream: fs2.Stream[IO, com.tark.ui.AgentAction[IO]]): IO[Unit] = {
      actionStream.evalMap {
        case com.tark.ui.AgentAction.RequestChoice(prompt, options, _, onSelected) =>
          IO.delay {
            capturedChoicePrompt = Some(prompt)
            capturedChoiceOptions = Some(options)
          } >> executeActions(onSelected("Yes"))
        case _ => IO.unit
      }.compile.drain
    }

    val program = engine
      .runConversation(context, messages, depth = 0, Vector.empty, resultRef)
      .evalMap { task => executeActions(task.action) }
      .compile
      .drain

    program.unsafeRunSync()

    assertEquals(capturedChoicePrompt, Some("Proceed?"))
    assertEquals(capturedChoiceOptions, Some(List("Yes", "No")))

    val result = resultRef.get.unsafeRunSync()
    assert(result.isDefined)
    assertEquals(result.get.finalAnswer, Some("Final decision: Yes"))
    
    // Check interactions:
    // 1st interaction is the questionnaire tool-call interaction
    // 2nd interaction is the final llm completion interaction
    assertEquals(result.get.interactions.size, 2)
    val firstInteraction = result.get.interactions.head
    assertEquals(firstInteraction.toolName, "questionnaire")
    assertEquals(firstInteraction.output, "Yes")
  }
}
