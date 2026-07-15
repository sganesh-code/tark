package com.tark.application.chat

import com.tark.application.chat.DefaultInputProcessor.given
import com.tark.application.instances.all.given
import com.tark.application.react.DefaultReActExecutor.given
import com.tark.application.time.Clock.given

import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain
import com.tark.domain.context.{Context, Session}
import com.tark.domain.memory.EpisodeSummary
import com.tark.domain.tool.{Tool, ToolCallRequest}

import java.nio.file.Path
import com.tark.domain.Interaction
import com.tark.ports.shared.tool.ToolCallDetector.given
import com.tark.ports.inbound.tool.SlashCommand.given
import com.tark.ports.inbound.tool.{ChatTransitions, InputProcessor, SlashCommand, SlashCommandRouter}
import com.tark.ports.inbound.ui.{Key, KeyboardAction, KeyboardHandler, KeyboardShortcuts}
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.react.{ReActLlmClient, ReActResponse}
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.shared.tool.{TextFormatter, ToolCallDetector}
import com.tark.ports.shared.ui.{ChatState, Message}

class DefaultInputProcessorSpec extends FunSuite {

  // A simple in-memory Sink for testing
  class InMemorySink extends Sink[IO, Context, Path] {
    var lastSaved: Option[Context] = None
    override def write(data: Context, destination: Path): IO[Unit] = IO {
      lastSaved = Some(data)
    }.void
  }

  private given noopSummarizer: EpisodicMemorySummarizer[IO] with {
    override def summarize(sessionId: String, history: List[Interaction]): IO[EpisodeSummary] =
      IO.pure(EpisodeSummary(sessionId, 0L, "No-op summary", List.empty))
  }

  test("ToolCallDetector: parses various patterns of tool calls") {
    val detector = summon[ToolCallDetector]
    
    assertEquals(detector.detect("EXECUTE_COMMAND: ls -la"), Some(ToolCallRequest("command_executor", Map("command" -> "ls -la"))))
    assertEquals(detector.detect("CALL_TOOL: test_tool args: file=main.scala"), Some(ToolCallRequest("test_tool", Map("file" -> "main.scala"))))
    assertEquals(detector.detect("execute: pwd"), Some(ToolCallRequest("command_executor", Map("command" -> "pwd"))))
    assertEquals(detector.detect("run: whoami"), Some(ToolCallRequest("command_executor", Map("command" -> "whoami"))))
    assertEquals(detector.detect("Just a normal conversation response"), None)

    // Tests with complex conversational preambles (conversational text before commands)
    val preambleExec = "To find dependency files, let's search of .sbt or gradle files in the folder.\nEXECUTE_COMMAND: find . -name '*.sbt' -o -name 'build.gradle'"
    assertEquals(detector.detect(preambleExec), Some(ToolCallRequest("command_executor", Map("command" -> "find . -name '*.sbt' -o -name 'build.gradle'"))))

    val preambleCall = "I'm invoking the custom files lookup tool with main.scala:\nCALL_TOOL: test_tool args: file=main.scala"
    assertEquals(detector.detect(preambleCall), Some(ToolCallRequest("test_tool", Map("file" -> "main.scala"))))

    val preambleExecute = "Let's see where we are currently running on the host:\nexecute: pwd"
    assertEquals(detector.detect(preambleExecute), Some(ToolCallRequest("command_executor", Map("command" -> "pwd"))))

    val preambleRun = "Let's check the user running the current shell:\nrun: whoami"
    assertEquals(detector.detect(preambleRun), Some(ToolCallRequest("command_executor", Map("command" -> "whoami"))))
  }

  test("SlashCommand: ExitCommand returns None") {
    val cmd = new SlashCommand.ExitCommand[IO]
    val state = ChatState(Vector.empty, "/exit")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    
    val result = cmd.execute(state, session).unsafeRunSync()
    assert(result.isEmpty)
  }

  test("SlashCommand: HelpCommand returns some help details and state") {
    val cmd = new SlashCommand.HelpCommand[IO]
    val state = ChatState(Vector.empty, "/help")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    
    val result = cmd.execute(state, session).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    assert(nextState.history.last.text.contains("Available commands"))
    assertEquals(nextState.prompt, "")
    assertEquals(nextSession, session)
  }

  test("ChatTransitions: append system, user and system, and clear prompt preserve ordering") {
    val state = ChatState(Vector(Message.System("existing")), "/help")

    val systemOnly = ChatTransitions.appendSystem(state, "status")
    assertEquals(systemOnly.history.map(_.text), Vector("existing", "status"))
    assertEquals(systemOnly.prompt, "/help")

    val userAndSystem = ChatTransitions.userAndSystem(state, "Available commands")
    assertEquals(userAndSystem.history.map(_.text), Vector("existing", "/help", "Available commands"))
    assertEquals(userAndSystem.prompt, "")

    val cleared = ChatTransitions.clearPrompt(state)
    assertEquals(cleared.history.map(_.text), Vector("existing"))
    assertEquals(cleared.prompt, "")
  }

  test("SlashCommand: ClearCommand clears history and writes context") {
    given sink: InMemorySink = new InMemorySink()
    given summarizer: EpisodicMemorySummarizer[IO] = new EpisodicMemorySummarizer[IO] {
      override def summarize(sessionId: String, history: List[Interaction]): IO[EpisodeSummary] = IO {
        com.tark.domain.memory.EpisodeSummary(sessionId, 5555L, "Distilled clear summary", List("Cleared history"))
      }
    }
    val cmd = new SlashCommand.ClearCommand[IO]
    
    val state = ChatState(Vector(Message.User("hello"), Message.AI("hi")), "/clear")
    val context = Context(Map.empty, Map.empty, List(
      Interaction("1", "hello", "hi", 12345L, "llm")
    ))
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    
    val result = cmd.execute(state, session).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    
    // Check that state history is cleared
    assertEquals(nextState.history, Vector.empty)
    assertEquals(nextState.prompt, "")
    
    // Check that session context history is cleared
    assertEquals(nextSession.context.history, List.empty)
    
    // Check that the updated context was saved
    assert(sink.lastSaved.isDefined)
    val savedContext = sink.lastSaved.get
    assertEquals(savedContext.history, List.empty)
    assertEquals(savedContext.memory.episodic.episodes.size, 1)
    assertEquals(savedContext.memory.episodic.episodes.head.summary, "Distilled clear summary")
  }

  test("SlashCommand: ClearCommand writes cleared context when history is empty") {
    given sink: InMemorySink = new InMemorySink()
    given summarizer: EpisodicMemorySummarizer[IO] = new EpisodicMemorySummarizer[IO] {
      override def summarize(sessionId: String, history: List[Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new RuntimeException("should not summarize empty history"))
    }
    val cmd = new SlashCommand.ClearCommand[IO]
    val session = Session("test_session", Context(Map.empty, Map.empty, List.empty), Path.of("target/test-sessions/test.md"))

    val result = cmd.execute(ChatState(Vector(Message.User("old")), "/clear"), session).unsafeRunSync()

    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    assertEquals(nextState, ChatState(Vector.empty, ""))
    assertEquals(nextSession.context.history, List.empty)
    assert(sink.lastSaved.isDefined)
    assertEquals(sink.lastSaved.get.history, List.empty)
    assertEquals(sink.lastSaved.get.memory.episodic.episodes, List.empty)
  }

  test("SlashCommand: ExitCommand persists fallback summary when summarizer fails") {
    given sink: InMemorySink = new InMemorySink()
    given summarizer: EpisodicMemorySummarizer[IO] = new EpisodicMemorySummarizer[IO] {
      override def summarize(sessionId: String, history: List[Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new RuntimeException("model unavailable"))
    }
    val cmd = new SlashCommand.ExitCommand[IO]
    val session = Session(
      "test_session",
      Context(Map.empty, Map.empty, List(Interaction("1", "hello", "hi", 12345L, "llm"))),
      Path.of("target/test-sessions/test.md")
    )

    val result = cmd.execute(ChatState(Vector(Message.User("hello")), "/exit"), session).unsafeRunSync()

    assertEquals(result, None)
    assert(sink.lastSaved.isDefined)
    val episode = sink.lastSaved.get.memory.episodic.episodes.head
    assertEquals(episode.sessionId, "test_session")
    assertEquals(episode.summary, "Exited with summarization error: model unavailable")
    assertEquals(episode.keyTakeaways, List.empty)
  }

  test("SlashCommand: ClearCommand persists fallback summary when summarizer fails") {
    given sink: InMemorySink = new InMemorySink()
    given summarizer: EpisodicMemorySummarizer[IO] = new EpisodicMemorySummarizer[IO] {
      override def summarize(sessionId: String, history: List[Interaction]): IO[EpisodeSummary] =
        IO.raiseError(new RuntimeException("model unavailable"))
    }
    val cmd = new SlashCommand.ClearCommand[IO]
    val session = Session(
      "test_session",
      Context(Map.empty, Map.empty, List(Interaction("1", "hello", "hi", 12345L, "llm"))),
      Path.of("target/test-sessions/test.md")
    )

    val result = cmd.execute(ChatState(Vector(Message.User("hello")), "/clear"), session).unsafeRunSync()

    assert(result.isDefined)
    val (_, nextSession) = result.get
    assertEquals(nextSession.context.history, List.empty)
    val episode = nextSession.context.memory.episodic.episodes.head
    assertEquals(episode.summary, "Cleared with summarization error: model unavailable")
    assertEquals(sink.lastSaved.map(_.history), Some(List.empty))
  }

  test("InputProcessor: dispatches slash commands cleanly") {
    given sink: InMemorySink = new InMemorySink()
    val state = ChatState(Vector.empty, "/help")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    
    given llmClient: LlmClient[IO] = new LlmClient[IO] {
      override def getCompletion(prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]): IO[Either[String, List[ToolCallRequest]]] = IO.pure(Left("unused"))
    }
    val processor = summon[InputProcessor[IO]]

    var redrawCalled = false
    val redraw: ChatState => IO[Unit] = _ => IO { redrawCalled = true }

    val result = processor.process("/help", state, session, redraw).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    assert(nextState.history.last.text.contains("Available commands"))
    assert(!redrawCalled) // Redraw is not called for slash commands
  }

  test("InputProcessor: processes normal prompt with LLM when no tool call detected") {
    given sink: InMemorySink = new InMemorySink()
    val state = ChatState(Vector.empty, "Hello AI")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    
    given llmClient: LlmClient[IO] = new LlmClient[IO] {
      override def getCompletion(prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]): IO[Either[String, List[ToolCallRequest]]] = IO.pure(Left("Hello human!"))
    }
    val processor = summon[InputProcessor[IO]]

    val redrawStates = scala.collection.mutable.Buffer.empty[ChatState]
    val redraw: ChatState => IO[Unit] = s => IO { redrawStates.append(s) }

    val result = processor.process("Hello AI", state, session, redraw).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    
    // Should append the response to history
    assert(nextState.history.exists(_.text.contains("Hello human!")))
    
    // Redraw should have been called with thinking state
    assert(redrawStates.exists(_.currentThought.isDefined))

    // Interactions should be recorded in session context and saved to Sink
    assert(nextSession.context.history.nonEmpty)
    val savedInteraction = nextSession.context.history.head
    assertEquals(savedInteraction.input, "Hello AI")
    assertEquals(savedInteraction.output, "Hello human!")
    assertEquals(savedInteraction.toolName, "llm_completion")
    
    assert(sink.lastSaved.isDefined)
    assertEquals(sink.lastSaved.get.history.head.output, "Hello human!")
  }

  test("InputProcessor: processes prompt with tool call, execution and follow-up") {
    given sink: InMemorySink = new InMemorySink()
    
    // Register a dummy tool
    val dummyTool = Tool("command_executor", _ => "file1.txt\nfile2.txt")

    import com.tark.ports.shared.tool.ToolRegistry
    val initialContext = Context(Map.empty, Map.empty, List.empty)
    val contextWithTool = summon[ToolRegistry[Context]].register(initialContext, dummyTool)
    val session = Session("test_session", contextWithTool, Path.of("target/test-sessions/test.md"))
    val state = ChatState(Vector.empty, "Run list files")

    given llmClient: LlmClient[IO] = new LlmClient[IO] {
      var callCount = 0
      override def getCompletion(prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]): IO[Either[String, List[ToolCallRequest]]] = IO {
        callCount += 1
        if (callCount == 1) {
          Right(List(ToolCallRequest("command_executor", Map("command" -> "ls"))))
        } else {
          Left(s"Based on the output, the files are: $prompt")
        }
      }
    }
    val processor = summon[InputProcessor[IO]]

    val redrawStates = scala.collection.mutable.Buffer.empty[ChatState]
    val redraw: ChatState => IO[Unit] = s => IO { redrawStates.append(s) }

    val result = processor.process("Run list files", state, session, redraw).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    
    // Check that we got final answer
    assert(nextState.history.last.text.contains("Based on the output, the files are:"))

    // Check that redraw was called during thinking and executing phases
    assert(redrawStates.exists(_.currentThought.isDefined))
    assert(redrawStates.exists(_.history.last.text.contains("[EXECUTING] -> file1.txt")))

    // Check interactions in the final context
    val history = nextSession.context.history
    assertEquals(history.length, 2) // Tool interaction and final Answer interaction
    
    val toolInteraction = history.head
    assertEquals(toolInteraction.toolName, "command_executor")
    assertEquals(toolInteraction.output, "file1.txt\nfile2.txt")
    
    val finalInteraction = history.last
    assertEquals(finalInteraction.toolName, "llm_completion")
    assert(finalInteraction.output.contains("Based on the output, the files are:"))
  }

  test("KeyboardHandler: returns Exit on Ctrl+C (3)") {
    val state = ChatState(Vector.empty, "prompt")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test", context, Path.of("target/test-sessions/test.md"))
    val redraw: ChatState => IO[Unit] = _ => IO.unit
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left(""))
    
    val handler = summon[KeyboardHandler[IO]]
    val result = handler.handleKey(3, state, session, redraw).unsafeRunSync()
    assertEquals(result, KeyboardAction.Exit)
  }

  test("KeyboardHandler: returns Continue with Backspace (127)") {
    val state = ChatState(Vector.empty, "abc")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test", context, Path.of("target/test-sessions/test.md"))
    val redraw: ChatState => IO[Unit] = _ => IO.unit
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left(""))
    
    val handler = summon[KeyboardHandler[IO]]
    val result = handler.handleKey(127, state, session, redraw).unsafeRunSync()
    assertEquals(result, KeyboardAction.Continue(ChatState(Vector.empty, "ab"), session))
    
    // Backspacing empty prompt should stay empty
    val emptyResult = handler.handleKey(127, ChatState(Vector.empty, ""), session, redraw).unsafeRunSync()
    assertEquals(emptyResult, KeyboardAction.Continue(ChatState(Vector.empty, ""), session))
  }

  test("KeyboardHandler: returns Continue on standard character keystroke") {
    val state = ChatState(Vector.empty, "abc")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test", context, Path.of("target/test-sessions/test.md"))
    val redraw: ChatState => IO[Unit] = _ => IO.unit
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left(""))
    
    val handler = summon[KeyboardHandler[IO]]
    val result = handler.handleKey('d'.toInt, state, session, redraw).unsafeRunSync()
    assertEquals(result, KeyboardAction.Continue(ChatState(Vector.empty, "abcd"), session))
  }

  test("KeyboardHandler: processes prompt and returns Continue on Enter (10/13)") {
    given sink: InMemorySink = new InMemorySink()
    val state = ChatState(Vector.empty, "/help")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test", context, Path.of("target/test-sessions/test.md"))
    val redraw: ChatState => IO[Unit] = _ => IO.unit
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left(""))
    
    val handler = summon[KeyboardHandler[IO]]
    val result10 = handler.handleKey(10, state, session, redraw).unsafeRunSync()
    val result13 = handler.handleKey(13, state, session, redraw).unsafeRunSync()
    
    assert(result10.isInstanceOf[KeyboardAction.Continue])
    assert(result13.isInstanceOf[KeyboardAction.Continue])
    
    val action10 = result10.asInstanceOf[KeyboardAction.Continue]
    assert(action10.state.history.last.text.contains("Available commands"))
  }

  test("KeyboardHandler: supports custom shortcuts and overrides defaults") {
    val state = ChatState(Vector.empty, "original")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test", context, Path.of("target/test-sessions/test.md"))
    val redraw: ChatState => IO[Unit] = _ => IO.unit
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left(""))

    // We define a custom shortcut locally inside the test
    given customShortcuts: KeyboardShortcuts[IO] = {
      // Overriding standard CtrlC to not Exit, but instead set a special prompt
      case Key.CtrlC => (state, session, _, _) =>
        IO.pure(KeyboardAction.Continue(state.copy(prompt = "intercepted_ctrl_c"), session))
      // Adding a completely new custom shortcut mapped to '?'
      case Key.Printable('?') => (state, session, _, _) =>
        IO.pure(KeyboardAction.Continue(state.copy(prompt = "help_triggered"), session))
    }

    val handler = summon[KeyboardHandler[IO]]

    // Test overriding Ctrl+C (keycode 3)
    val resultCtrlC = handler.handleKey(3, state, session, redraw).unsafeRunSync()
    assertEquals(resultCtrlC, KeyboardAction.Continue(state.copy(prompt = "intercepted_ctrl_c"), session))

    // Test custom key '?'
    val resultQuestion = handler.handleKey('?'.toInt, state, session, redraw).unsafeRunSync()
    assertEquals(resultQuestion, KeyboardAction.Continue(state.copy(prompt = "help_triggered"), session))

    // Test that other default shortcuts (like Backspace - 127) still fall back to their default behavior
    val resultBackspace = handler.handleKey(127, ChatState(Vector.empty, "abc"), session, redraw).unsafeRunSync()
    assertEquals(resultBackspace, KeyboardAction.Continue(ChatState(Vector.empty, "ab"), session))
  }

  test("InputProcessor: respects maxDepth and stops with depth error when tool calls loop infinitely") {
    given sink: InMemorySink = new InMemorySink()
    
    val dummyTool = Tool("command_executor", _ => "loop_output")
    import com.tark.ports.shared.tool.ToolRegistry
    val initialContext = Context(Map.empty, Map.empty, List.empty)
    val contextWithTool = summon[ToolRegistry[Context]].register(initialContext, dummyTool)
    val session = Session("test_session", contextWithTool, Path.of("target/test-sessions/test.md"))
    val state = ChatState(Vector.empty, "Run loop")

    // This stub always returns a tool call request, causing an infinite loop unless stopped by maxDepth
    given llmClient: LlmClient[IO] = new LlmClient[IO] {
      override def getCompletion(prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]): IO[Either[String, List[ToolCallRequest]]] = IO {
        Right(List(ToolCallRequest("command_executor", Map("command" -> "ls"))))
      }
    }
    val processor = summon[InputProcessor[IO]]

    val redraw: ChatState => IO[Unit] = _ => IO.unit

    val result = processor.process("Run loop", state, session, redraw).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    
    // Should end with the maximum execution depth exceeded error message
    assert(nextState.history.last.text.contains("depth exceeded"))
    assert(nextSession.context.history.last.output.contains("depth exceeded"))
  }

  test("SlashCommand: ExitCommand summarizes history on exit and writes context") {
    given sink: InMemorySink = new InMemorySink()
    given summarizer: EpisodicMemorySummarizer[IO] = new EpisodicMemorySummarizer[IO] {
      override def summarize(sessionId: String, history: List[Interaction]): IO[EpisodeSummary] = IO {
        com.tark.domain.memory.EpisodeSummary(sessionId, 9999L, "Distilled exit summary", List("Saved on exit"))
      }
    }
    val cmd = new SlashCommand.ExitCommand[IO]

    val state = ChatState(Vector(Message.User("hello"), Message.AI("hi")), "/exit")
    val context = Context(Map.empty, Map.empty, List(
      Interaction("1", "hello", "hi", 12345L, "llm")
    ))
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))

    val result = cmd.execute(state, session).unsafeRunSync()
    assertEquals(result, None)

    assert(sink.lastSaved.isDefined)
    val savedContext = sink.lastSaved.get
    assertEquals(savedContext.memory.episodic.episodes.size, 1)
    assertEquals(savedContext.memory.episodic.episodes.head.summary, "Distilled exit summary")
    assertEquals(savedContext.memory.episodic.episodes.head.keyTakeaways, List("Saved on exit"))
  }

  test("SlashCommand: MemoryCommand displays memory layers successfully") {
    given sink: InMemorySink = new InMemorySink()
    val cmd = new SlashCommand.MemoryCommand[IO]

    val working = com.tark.domain.AgentState(goal = "Test Command", done = false)
    val episode = com.tark.domain.memory.EpisodeSummary("sess_1", 1000L, "Prior summary", List("takeaway 1"))
    val memory = com.tark.domain.memory.Memory(
      working = Some(working),
      episodic = com.tark.domain.memory.EpisodicMemory(List(episode))
    )
    val context = Context(Map.empty, memory, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    val state = ChatState(Vector.empty, "/memory")

    val result = cmd.execute(state, session).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get

    assert(nextState.history.last.text.contains("[Memory Layers Status]"))
    assert(nextState.history.last.text.contains("Test Command"))
    assert(nextState.history.last.text.contains("Prior summary"))
    assert(nextState.history.last.text.contains("takeaway 1"))
  }

  test("TextFormatter.limitToolOutput: correctly limits tool response lines") {
    val shortOutput = "line1\nline2\nline3"
    assertEquals(TextFormatter.limitToolOutput(shortOutput, 5), shortOutput)

    val longOutput = (1 to 25).map(i => s"line$i").mkString("\n")
    val truncated = TextFormatter.limitToolOutput(longOutput, 10)
    
    assert(truncated.contains("line10"))
    assert(!truncated.contains("line11"))
    assert(truncated.contains("TRUNCATED - 15 more lines"))
  }

  test("InputProcessor: supports dynamically custom registered slash command") {
    given sink: InMemorySink = new InMemorySink()
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left("unused"))
    
    val customCmd = new SlashCommand[IO] {
      override val name: String = "/ping"
      override def execute(state: ChatState, session: Session): IO[Option[(ChatState, Session)]] = {
        val nextState = state.copy(
          history = (state.history :+ Message.User(state.prompt)) :+ Message.System("pong"),
          prompt = ""
        )
        IO.pure(Some((nextState, session)))
      }
    }
    
    given customSlashCommands: List[SlashCommand[IO]] = List(customCmd)
    
    val processor = summon[InputProcessor[IO]]
    
    val state = ChatState(Vector.empty, "/ping")
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    
    val redraw: ChatState => IO[Unit] = _ => IO.unit
    
    val result = processor.process("/ping", state, session, redraw).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get
    assertEquals(nextState.history.last.text, "pong")
  }

  test("SlashCommandRouter laws: exact first-token match and fallback for unknown commands") {
    given sink: InMemorySink = new InMemorySink()
    val customCmd = new SlashCommand[IO] {
      override val name: String = "/ping"
      override def execute(state: ChatState, session: Session): IO[Option[(ChatState, Session)]] =
        IO.pure(Some((state.copy(history = state.history :+ Message.System("pong")), session)))
    }
    given customSlashCommands: List[SlashCommand[IO]] = List(customCmd)

    val router = summon[SlashCommandRouter[IO]]
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    val redraw: ChatState => IO[Unit] = _ => IO.unit

    val exactResult = router.process("/ping with args", ChatState(Vector.empty, "/ping with args"), session, redraw).unsafeRunSync()
    assert(exactResult.isDefined)
    assertEquals(exactResult.get._1.history.last.text, "pong")

    val unknownResult = router.process("/pingextra", ChatState(Vector.empty, "/pingextra"), session, redraw).unsafeRunSync()
    assert(unknownResult.isDefined)
    assertEquals(unknownResult.get._1.history.last.text, "Unknown command: /pingextra")
  }

  test("SlashCommandRouter laws: duplicate command names use first registered command") {
    given sink: InMemorySink = new InMemorySink()
    val first = new SlashCommand[IO] {
      override val name: String = "/dupe"
      override def execute(state: ChatState, session: Session): IO[Option[(ChatState, Session)]] =
        IO.pure(Some((state.copy(history = state.history :+ Message.System("first")), session)))
    }
    val second = new SlashCommand[IO] {
      override val name: String = "/dupe"
      override def execute(state: ChatState, session: Session): IO[Option[(ChatState, Session)]] =
        IO.pure(Some((state.copy(history = state.history :+ Message.System("second")), session)))
    }
    given customSlashCommands: List[SlashCommand[IO]] = List(first, second)

    val router = summon[SlashCommandRouter[IO]]
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session", context, Path.of("target/test-sessions/test.md"))
    val redraw: ChatState => IO[Unit] = _ => IO.unit

    val result = router.process("/dupe", ChatState(Vector.empty, "/dupe"), session, redraw).unsafeRunSync()
    assert(result.isDefined)
    assertEquals(result.get._1.history.last.text, "first")
  }
}
