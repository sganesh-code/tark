package com.tark.adapters.backend.ollama

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.application.chat.DefaultInputProcessor.given
import com.tark.application.instances.all.given
import com.tark.application.react.DefaultReActExecutor.given
import com.tark.application.time.Clock.given
import com.tark.bootstrap.OllamaRuntime.given
import com.tark.domain.Interaction
import com.tark.domain.context.{Context, Session}
import com.tark.domain.memory.{EpisodicMemory, Memory}
import com.tark.domain.tool.{Tool, ToolCallRequest}
import com.tark.ports.inbound.tool.{InputProcessor, SlashCommand}
import com.tark.ports.inbound.tool.SlashCommand.given
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.outbound.react.{ReActLlmClient, ReActResponse, ReActStrategy}
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.shared.tool.ToolRegistry
import com.tark.ports.shared.ui.{ChatState, Message}
import io.circe.syntax.*
import munit.FunSuite
import sttp.client3.httpclient.cats.HttpClientCatsBackend

import java.nio.file.Path

class OllamaInputProcessorIntegrationSpec extends FunSuite {
  class InMemorySink extends Sink[IO, Context, Path] {
    var lastSaved: Option[Context] = None
    override def write(data: Context, destination: Path): IO[Unit] = IO {
      lastSaved = Some(data)
    }.void
  }

  test("InputProcessor: integration test with OllamaReActLlmClient and JsonReActStrategy") {
    given sink: InMemorySink = new InMemorySink()

    val dummyTool = Tool("command_executor", _ => "file1.txt\nfile2.txt")
    val initialContext = Context(Map.empty, Map.empty, List.empty)
    val contextWithTool = summon[ToolRegistry[Context]].register(initialContext, dummyTool)
    val session = Session("test_session", contextWithTool, Path.of("target/test-sessions/test_session.md"))
    val state = ChatState(Vector.empty, "Tell me about this project")

    val step1ResponseRaw = """{
      |  "thought": "I need to explore the project structure to understand what kind of project this is. I'll start by checking what files exist.",
      |  "tool": "command_executor",
      |  "arguments": {
      |    "command": "ls -la"
      |  }
      |}""".stripMargin

    val step2ResponseRaw = """{
      |  "thought": "I see there are files in the directory. I can conclude the task now.",
      |  "tool": "conclude_task",
      |  "arguments": {
      |    "final_answer": "This project is a CLI tool."
      |  }
      |}""".stripMargin

    val step1OllamaResponse = s"""{
      |  "choices": [
      |    {
      |      "message": {
      |        "role": "assistant",
      |        "content": ${step1ResponseRaw.asJson.noSpaces}
      |      }
      |    }
      |  ]
      |}""".stripMargin

    val step2OllamaResponse = s"""{
      |  "choices": [
      |    {
      |      "message": {
      |        "role": "assistant",
      |        "content": ${step2ResponseRaw.asJson.noSpaces}
      |      }
      |    }
      |  ]
      |}""".stripMargin

    var callCount = 0
    val testingBackend = HttpClientCatsBackend.stub[IO]
      .whenRequestMatches(_.uri.path.contains("completions"))
      .thenRespondF { _ =>
        IO {
          callCount += 1
          if (callCount == 1) {
            sttp.client3.Response.ok(step1OllamaResponse)
          } else {
            sttp.client3.Response.ok(step2OllamaResponse)
          }
        }
      }

    given strategy: ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] = new OllamaReActStrategy.JsonReActStrategy()
    given reactLlm: ReActLlmClient[IO] = new OllamaReActLlmClient(testingBackend)(using strategy)
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left("unused"))

    val processor = summon[InputProcessor[IO]]
    val redrawStates = scala.collection.mutable.Buffer.empty[ChatState]
    val redraw: ChatState => IO[Unit] = s => IO { redrawStates.append(s) }

    val result = processor.process("Tell me about this project", state, session, redraw).unsafeRunSync()
    assert(result.isDefined)
    val (nextState, nextSession) = result.get

    assertEquals(callCount, 2)
    assert(nextState.history.last.text.contains("This project is a CLI tool."))

    val history = nextSession.context.history
    assertEquals(history.length, 2)
    assertEquals(history.head.toolName, "command_executor")
    assertEquals(history.head.output, "file1.txt\nfile2.txt")
    assertEquals(history.last.toolName, "llm_completion")
    assert(history.last.output.contains("This project is a CLI tool."))
  }

  test("InputProcessor: preserves conversational history and prior session episodic memories across turns with Ollama client") {
    given sink: InMemorySink = new InMemorySink()

    val episode = com.tark.domain.memory.EpisodeSummary(
      sessionId = "session_old",
      timestamp = 1000L,
      summary = "Learned that the project name is tark and uses Scala",
      keyTakeaways = List("Scala project", "Name is tark")
    )
    val priorMemory = Memory(
      episodic = EpisodicMemory(List(episode))
    )

    val previousInteraction = Interaction(
      id = "interaction_prev",
      input = "Hello AI",
      output = "Hello human!",
      timestamp = 5000L,
      toolName = "llm_completion"
    )

    val dummyTool = Tool("command_executor", _ => "file1.txt\nfile2.txt")
    val initialContext = Context(Map.empty, priorMemory, List(previousInteraction))
    val contextWithTool = summon[ToolRegistry[Context]].register(initialContext, dummyTool)
    val session = Session("test_session_new", contextWithTool, Path.of("target/test-sessions/test_session.md"))
    val state = ChatState(Vector(Message.User("Hello AI"), Message.AI("Hello human!")), "What is the name of this project?")

    val responseRaw = """{
      |  "thought": "I know from prior sessions that the name of this project is tark.",
      |  "tool": "conclude_task",
      |  "arguments": {
      |    "final_answer": "The project is named tark."
      |  }
      |}""".stripMargin

    val ollamaResponse = s"""{
      |  "choices": [
      |    {
      |      "message": {
      |        "role": "assistant",
      |        "content": ${responseRaw.asJson.noSpaces}
      |      }
      |    }
      |  ]
      |}""".stripMargin

    var capturedHistory: List[Interaction] = List.empty
    var capturedUserPrompt: String = ""

    val testingBackend = HttpClientCatsBackend.stub[IO]
      .whenRequestMatches(_.uri.path.contains("completions"))
      .thenRespondF { request =>
        IO {
          import io.circe.parser.*
          import sttp.client3.testing.RichTestingRequest
          val bodyStr = request.forceBodyAsString
          parse(bodyStr).toOption.foreach { json =>
            val cursor = json.hcursor
            val messagesOpt = cursor.get[List[OllamaMessage]]("messages").toOption
            val lastUserMsg = messagesOpt.flatMap(_.filter(_.role == "user").lastOption).flatMap(_.content)
            lastUserMsg.foreach { prompt =>
              capturedUserPrompt = prompt
            }
          }
          sttp.client3.Response.ok(ollamaResponse)
        }
      }

    given strategy: ReActStrategy[IO, OllamaMessage, OllamaRequest, OllamaResponse] = new OllamaReActStrategy.JsonReActStrategy()
    given reactLlm: ReActLlmClient[IO] = new OllamaReActLlmClient(testingBackend)(using strategy) {
      override def getCompletion(prompt: String, history: List[Interaction], systemPrompt: String, tools: List[Tool]): IO[Either[String, ReActResponse]] = {
        capturedHistory = history
        super.getCompletion(prompt, history, systemPrompt, tools)
      }
    }
    given llmClient: LlmClient[IO] = (_, _, _, _) => IO.pure(Left("unused"))

    val processor = summon[InputProcessor[IO]]
    val redraw: ChatState => IO[Unit] = _ => IO.unit

    val result = processor.process("What is the name of this project?", state, session, redraw).unsafeRunSync()
    assert(result.isDefined)

    assert(capturedHistory.nonEmpty)
    assertEquals(capturedHistory.head.input, "Hello AI")
    assertEquals(capturedHistory.head.output, "Hello human!")
    assert(capturedUserPrompt.contains("Context from Prior Sessions:"))
    assert(capturedUserPrompt.contains("Learned that the project name is tark and uses Scala"))
    assert(capturedUserPrompt.contains("Scala project"))
  }
}
