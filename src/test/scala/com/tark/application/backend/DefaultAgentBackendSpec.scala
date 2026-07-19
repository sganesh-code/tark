package com.tark.application.backend

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.context.{Context, Session}
import com.tark.domain.memory.{EpisodeSummary, Memory}
import com.tark.domain.tool.{OpenAIFunction, OpenAIFunctionParams, OpenAIUsage, ToolCall, ToolCallFunction, ToolDefinition, ToolResult}
import com.tark.ports.AgentBackend
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, LlmStreamEvent, Prompt, StreamingLlmClient}
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.tool.CommandExecutor
import com.tark.ports.shared.serialization.Sink
import com.tark.ui.AgentAction
import fs2.Stream
import munit.FunSuite

import java.nio.file.Path
import scala.collection.mutable.ArrayBuffer

class DefaultAgentBackendSpec extends FunSuite {
  private val commandTool =
    ToolDefinition(
      `type` = "function",
      function = OpenAIFunction(
        name = "command_executor",
        description = "Execute a command",
        parameters = OpenAIFunctionParams.Str(description = "command")
      )
    )

  test("plain assistant response emits action stream and persists AgentState messages") {
    var written: Option[Context] = None

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay {
        written = Some(data)
      }
    }

    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }

    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(1000L)
    }

    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }

    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.pure(LLMResponse("plain answer", List.empty, OpenAIUsage(10, 5, 15)))
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val (tasks, actions) = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- executeSequentially(backend, "hello")
    } yield (results.map(_._1), results.flatMap(_._2))).unsafeRunSync()

    assertEquals(tasks, List(Some("Waiting for assistant response"), Some("Finalizing assistant response"), Some("Persisting session")))
    assert(actions.exists { case AgentAction.AssistantDelta("plain answer") => true; case _ => false })
    assert(actions.exists { case AgentAction.AssistantEnd() => true; case _ => false })
    assert(actions.contains(AgentAction.StatusUpdate("LLM Usage: Prompt 10 | Completion 5 | Total 15")))

    val persisted = written.get
    val working = persisted.memory.working.get
    assertEquals(persisted.history.map(_.output), List("plain answer"))
    assertEquals(working.candidateAnswer, Some("plain answer"))
    assertEquals(working.messages.map(_.role), List("user", "assistant"))
    assertEquals(working.messages.last.content, Some("plain answer"))
  }

  test("/run emits command result and persists command interaction") {
    var written: Option[Context] = None

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay {
        written = Some(data)
      }
    }

    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }

    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(2000L)
    }

    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.pure(ToolResult("command output"))
    }

    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("llm should not be invoked"))
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val actions = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- executeSequentially(backend, "/run echo hi")
      actions = results.flatMap(_._2)
    } yield actions).unsafeRunSync()

    assert(actions.exists { case AgentAction.Log("[EXECUTING] -> echo hi") => true; case _ => false })
    assert(actions.exists { case AgentAction.SystemMessage("command output") => true; case _ => false })
    assertEquals(written.get.history.map(_.input), List("/run echo hi"))
    assertEquals(written.get.history.map(_.toolName), List("command_executor"))
  }

  test("/help emits list of available commands") {
    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.unit
    }
    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }
    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(1000L)
    }
    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }
    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("llm should not be invoked"))
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val actions = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- executeSequentially(backend, "/help")
      actions = results.flatMap(_._2)
    } yield actions).unsafeRunSync()

    assert(actions.exists {
      case AgentAction.SystemMessage(msg) => msg.contains("Available commands:")
      case _ => false
    })
  }

  test("/memory renders memory layers status") {
    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.unit
    }
    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }
    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(1000L)
    }
    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }
    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("llm should not be invoked"))
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val actions = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- executeSequentially(backend, "/memory")
      actions = results.flatMap(_._2)
    } yield actions).unsafeRunSync()

    assert(actions.exists {
      case AgentAction.SystemMessage(msg) => msg.contains("[Memory Layers Status]")
      case _ => false
    })
  }

  test("/clear summarizes, persists, clears session, and clears screen") {
    var written: Option[Context] = None

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay {
        written = Some(data)
      }
    }
    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.pure(EpisodeSummary(sessionId, 2000L, "summarized history", List("takeaway")))
    }
    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(2000L)
    }
    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }
    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("llm should not be invoked"))
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val history = List(com.tark.domain.Interaction("1", "input", "output", 1000L, "tool"))
    val session = Session("session_1", Context(List(commandTool), Memory(), history), Path.of("target/test-session.md"))

    val actions = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- executeSequentially(backend, "/clear")
      actions = results.flatMap(_._2)
    } yield actions).unsafeRunSync()

    assert(actions.exists { case AgentAction.ClearScreen() => true; case _ => false })
    assert(actions.exists { case AgentAction.SystemMessage(msg) if msg.contains("Session cleared.") => true; case _ => false })

    val persisted = written.get
    assert(persisted.history.isEmpty)
    assertEquals(persisted.memory.episodic.episodes.map(_.summary), List("summarized history"))
  }

  test("/exit summarizes and exits session") {
    var written: Option[Context] = None

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay {
        written = Some(data)
      }
    }
    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.pure(EpisodeSummary(sessionId, 2000L, "exited history", List.empty))
    }
    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(2000L)
    }
    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }
    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("llm should not be invoked"))
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val history = List(com.tark.domain.Interaction("1", "input", "output", 1000L, "tool"))
    val session = Session("session_1", Context(List(commandTool), Memory(), history), Path.of("target/test-session.md"))

    val actions = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- executeSequentially(backend, "/exit")
      actions = results.flatMap(_._2)
    } yield actions).unsafeRunSync()

    assert(actions.exists { case AgentAction.Exit() => true; case _ => false })
    val persisted = written.get
    assertEquals(persisted.history, history) // exit doesn't clear history
    assertEquals(persisted.memory.episodic.episodes.map(_.summary), List("exited history"))
  }

  test("tool conversation emits assistant and tool messages before persistence") {
    val events = ArrayBuffer.empty[String]
    var chatCalls = 0
    var now = 3000L

    val toolCall = ToolCall(
      id = "call_1",
      `type` = "function",
      function = ToolCallFunction("command_executor", """{"command":"echo hi"}""")
    )

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay {
        events += "persist"
      }
    }

    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }

    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.delay {
        val current = now
        now = now + 1
        current
      }
    }

    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.delay(events += "execute-tool").as(ToolResult("tool output"))
    }

    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.delay {
          chatCalls = chatCalls + 1
          if chatCalls == 1 then LLMResponse("I need a tool", List(toolCall), OpenAIUsage(5, 5, 10))
          else LLMResponse("final answer", List.empty, OpenAIUsage(3, 2, 5))
        }
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val results = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- backend
        .handleInput("use a tool")
        .evalMap { task =>
          IO.delay(events += s"task:${task.description.getOrElse("")}") >>
            task.action
              .evalTap {
              case AgentAction.Log(text)           => IO.delay(events += s"action:$text").void
              case AgentAction.AssistantDelta(text) => IO.delay(events += s"action:$text").void
              case AgentAction.SystemMessage(text) => IO.delay(events += s"action:$text").void
              case other                           => IO.delay(events += s"action:$other").void
              }
              .compile
              .toList
              .map(actions => task.description -> actions)
        }
        .compile
        .toList
    } yield results).unsafeRunSync()

    assertEquals(
      results.map(_._1),
      List(
        Some("Waiting for assistant response"),
        None,
        Some("Waiting for assistant response after tool results"),
        Some("Finalizing assistant response"),
        Some("Persisting session")
      )
    )
    assert(events.indexOf("action:I need a tool") < events.indexOf("execute-tool"))
    assert(events.indexOf("action:tool output") < events.indexOf("persist"))
    assert(events.indexOf("action:final answer") < events.indexOf("persist"))
  }

  test("streaming tool call deltas are internal until a complete tool call is executed") {
    val events = ArrayBuffer.empty[String]
    var now = 4000L
    var promptCount = 0

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay(events += "persist")
    }

    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }

    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.delay {
        val current = now
        now = now + 1
        current
      }
    }

    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.delay(events += s"execute:${toolCall.function.arguments}").as(ToolResult("tool output"))
    }

    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.raiseError(new AssertionError("buffered fallback should not be invoked"))
    }

    given StreamingLlmClient[IO] with {
      override def chatStream(prompt: Prompt): Stream[IO, LlmStreamEvent] =
        Stream.eval(IO.delay {
          promptCount = promptCount + 1
          promptCount
        }).flatMap {
          case 1 =>
            Stream(
              LlmStreamEvent.ContentDelta("checking"),
              LlmStreamEvent.ToolCallDelta(index = 0, id = Some("call_1"), callType = Some("function")),
              LlmStreamEvent.ToolCallDelta(index = 0, name = Some("command_executor"), argumentsChunk = Some("""{"command":""")),
              LlmStreamEvent.ToolCallDelta(index = 0, argumentsChunk = Some(""""echo hi"}""")),
              LlmStreamEvent.Completed()
            )
          case _ =>
            Stream(LlmStreamEvent.ContentDelta("done"), LlmStreamEvent.Completed())
        }
    }

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val results = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- backend
        .handleInput("stream tool")
        .evalMap { task =>
          task.action
            .evalTap {
              case AgentAction.Log(text)           => IO.delay(events += s"action:$text").void
              case AgentAction.AssistantDelta(text) => IO.delay(events += s"action:$text").void
              case AgentAction.SystemMessage(text) => IO.delay(events += s"action:$text").void
              case _                               => IO.unit
            }
            .compile
            .toList
            .map(actions => task.description -> actions)
        }
        .compile
        .toList
    } yield results).unsafeRunSync()

    assertEquals(
      results.map(_._1),
      List(
        Some("Waiting for assistant response"),
        None,
        Some("Waiting for assistant response after tool results"),
        Some("Finalizing assistant response"),
        Some("Persisting session")
      )
    )
    assert(!events.exists(event => event.startsWith("action:") && event.contains("""{"command":""")))
    assert(events.contains("""execute:{"command":"echo hi"}"""))
    assert(events.indexOf("action:checking") < events.indexOf("""execute:{"command":"echo hi"}"""))
    assert(events.indexOf("action:tool output") < events.indexOf("persist"))
  }

  test("streaming failure falls back to buffered chat") {
    var written: Option[Context] = None

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay {
        written = Some(data)
      }
    }

    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }

    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(5000L)
    }

    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }

    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.pure(LLMResponse("fallback answer", List.empty, OpenAIUsage(2, 2, 4)))
    }

    given StreamingLlmClient[IO] with {
      override def chatStream(prompt: Prompt): Stream[IO, LlmStreamEvent] =
        Stream.raiseError[IO](RuntimeException("stream unsupported"))
    }

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val actions = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results <- executeSequentially(backend, "hello")
    } yield results.flatMap(_._2)).unsafeRunSync()

    assert(actions.exists { case AgentAction.SystemMessage(text) if text.contains("Falling back to buffered response") => true; case _ => false })
    assert(actions.exists { case AgentAction.Log("fallback answer") => true; case _ => false })
    assertEquals(written.get.history.map(_.output), List("fallback answer"))
  }

  test("DefaultAgentBackend accumulates usage over multiple LLM calls") {
    var written: Option[Context] = None

    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.delay {
        written = Some(data)
      }
    }

    given EpisodicMemorySummarizer[IO] with {
      override def summarize(sessionId: String, history: List[com.tark.domain.Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new AssertionError("summarizer should not be invoked"))
    }

    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(1000L)
    }

    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = commandTool
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }

    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.pure(LLMResponse("answer", List.empty, OpenAIUsage(10, 5, 15)))
    }
    given StreamingLlmClient[IO] = summon[LlmClient[IO]].streaming.getOrElse(StreamingLlmClient.fromBuffered(summon[LlmClient[IO]]))

    val session = Session("session_1", Context(List(commandTool), Memory(), List.empty), Path.of("target/test-session.md"))

    val actions = (for {
      backend <- DefaultAgentBackend.create[IO](session)
      results1 <- executeSequentially(backend, "first prompt")
      results2 <- executeSequentially(backend, "second prompt")
    } yield (results1 ++ results2).flatMap(_._2)).unsafeRunSync()

    assert(actions.contains(AgentAction.StatusUpdate("LLM Usage: Prompt 10 | Completion 5 | Total 15")))
    assert(actions.contains(AgentAction.StatusUpdate("LLM Usage: Prompt 20 | Completion 10 | Total 30")))
  }

  private def executeSequentially(
    backend: AgentBackend[IO],
    input: String
  ): IO[List[(Option[String], List[AgentAction[IO]])]] =
    backend
      .handleInput(input)
      .evalMap(task => task.action.compile.toList.map(actions => task.description -> actions))
      .compile
      .toList
}
