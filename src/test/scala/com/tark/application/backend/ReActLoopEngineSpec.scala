package com.tark.application.backend

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.tark.application.time.Clock
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.tool.{OpenAIMessage, ToolCall, ToolCallFunction, ToolResult, OpenAIUsage}
import com.tark.ports.outbound.backend.*
import com.tark.ports.outbound.tool.{CommandExecutor, DefaultToolCallExecutor, ToolCallExecutor}
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
    
    val executor = new CommandExecutor[IO] {
      override def definition = null
      override def execute(context: Context, toolCall: ToolCall) = IO.raiseError(new AssertionError("no tools"))
    }

    val clock = new Clock[IO] {
      override def realTimeMillis = IO.pure(12345L)
    }

    val toolCallExecutor = new DefaultToolCallExecutor[IO](executor)
    val distiller = new ContextDistiller[IO](llmClient)
    val engine = new ReActLoopEngine[IO](handler, toolCallExecutor, clock, distiller, config)
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

    val executor = new CommandExecutor[IO] {
      override def definition = null
      override def execute(context: Context, toolCall: ToolCall) = IO.raiseError(new AssertionError("command executor should not be called"))
    }

    val clock = new Clock[IO] {
      override def realTimeMillis = IO.pure(12345L)
    }

    val toolCallExecutor = new DefaultToolCallExecutor[IO](executor)
    val distiller = new ContextDistiller[IO](llmClient)
    val engine = new ReActLoopEngine[IO](handler, toolCallExecutor, clock, distiller, config)
    val context = Context(List.empty, Memory(), List.empty)
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

  test("ReActLoopEngine triggers context distillation on tool output exceeding threshold") {
    val usageRef = Ref.unsafe[IO, OpenAIUsage](OpenAIUsage(0, 0, 0))
    val callCounter = Ref.unsafe[IO, Int](0)

    val rawLongOutput = "A" * 1500 // 1500 characters, exceeds default 1000 threshold

    val toolCall = ToolCall("call_cmd", "function", ToolCallFunction("command_executor", """{"command": "cat long.txt"}"""))

    val streamingClient = new StreamingLlmClient[IO] {
      override def chatStream(prompt: Prompt): fs2.Stream[IO, LlmStreamEvent] = {
        fs2.Stream.eval(callCounter.getAndUpdate(_ + 1)).flatMap {
          case 0 =>
            // Initial call: triggers tool call
            fs2.Stream(
              LlmStreamEvent.ToolCallDelta(
                index = 0,
                id = Some("call_cmd"),
                callType = Some("function"),
                name = Some("command_executor"),
                argumentsChunk = Some("""{"command": "cat long.txt"}""")
              ),
              LlmStreamEvent.Completed(
                Some(
                  LLMResponse(
                    content = "",
                    results = List(toolCall),
                    usage = OpenAIUsage(10, 5, 15)
                  )
                )
              )
            )
          case _ =>
            // Third call: final assistant response (after distillation is done)
            fs2.Stream(
              LlmStreamEvent.ContentDelta("All done with distillation!"),
              LlmStreamEvent.Completed(
                Some(
                  LLMResponse(
                    content = "All done with distillation!",
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
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] = {
        // Second call: ContextDistiller performs a blocking chat call to distill the raw output
        IO.pure(
          LLMResponse(
            content = "distilled output response",
            results = List.empty,
            usage = OpenAIUsage(5, 5, 10)
          )
        )
      }
    }

    val config = com.tark.domain.Config.default.copy(distillationThreshold = 1000, enableDistillation = true)
    val handler = new StreamingResponseHandler[IO](streamingClient, llmClient, usageRef, config)

    val executor = new CommandExecutor[IO] {
      override def definition = null
      override def execute(context: Context, toolCall: ToolCall) =
        IO.pure(ToolResult(rawLongOutput))
    }

    val clock = new Clock[IO] {
      override def realTimeMillis = IO.pure(12345L)
    }

    val toolCallExecutor = new DefaultToolCallExecutor[IO](executor)
    val distiller = new ContextDistiller[IO](llmClient)
    val engine = new ReActLoopEngine[IO](handler, toolCallExecutor, clock, distiller, config)
    val context = Context(List.empty, Memory(), List.empty)
    val messages = List(OpenAIMessage(role = "user", content = Some("Read long file")))
    val resultRef = Ref.unsafe[IO, Option[ConversationResult]](None)

    val actions = engine
      .runConversation(context, messages, depth = 0, Vector.empty, resultRef)
      .flatMap(_.action)
      .compile
      .toList
      .unsafeRunSync()

    // Verify context distillation system messages were emitted
    val systemMessages = actions.collect { case AgentAction.SystemMessage(text) => text }
    assert(systemMessages.exists(_.contains("Distilling tool output (1500 chars)")))
    assert(systemMessages.exists(_.contains("Completed. Distilled size: 25 chars.")))

    // Verify tool output in the action stream was distilled
    val toolOutputs = actions.collect { case AgentAction.ToolCallOutput(text) => text }
    assertEquals(toolOutputs, List("distilled output response"))

    val result = resultRef.get.unsafeRunSync()
    assert(result.isDefined)
    
    // Check that distilled result was passed to subsequent messages
    val toolMessage = result.get.messages.find(_.role == "tool")
    assert(toolMessage.isDefined)
    assertEquals(toolMessage.get.content, Some("distilled output response"))

    // Check that interaction output was distilled
    assertEquals(result.get.interactions.size, 2)
    val firstInteraction = result.get.interactions.head
    assertEquals(firstInteraction.toolName, "command_executor")
    assertEquals(firstInteraction.output, "distilled output response")
  }
}
