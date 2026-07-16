package com.tark.application.chat

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.adapters.tool.command.CommandTool
import com.tark.application.time.Clock
import com.tark.domain.context.{Context, Session}
import com.tark.domain.memory.Memory
import com.tark.domain.tool.{ToolCall, ToolDefinition, ToolResult}
import com.tark.ports.inbound.tool.{InputProcessor, SlashCommandRouter}
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, Prompt}
import com.tark.ports.outbound.tool.CommandExecutor
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.shared.ui.{ChatState, Message}
import munit.FunSuite

import java.nio.file.Path

class DefaultInputProcessorSpec extends FunSuite {
  test("plain assistant response without tool calls updates chat state and AgentState messages") {
    given Sink[IO, Context, Path] with {
      override def write(data: Context, destination: Path): IO[Unit] = IO.unit
    }

    given SlashCommandRouter[IO] with {
      override def process(
        input: String,
        state: ChatState,
        session: Session,
        redraw: ChatState => IO[Unit]
      ): IO[Option[(ChatState, Session)]] =
        IO.pure(Some((state, session)))
    }

    given Clock[IO] with {
      override def realTimeMillis: IO[Long] = IO.pure(1000L)
    }

    given CommandExecutor[IO] with {
      override def definition: ToolDefinition = CommandTool.definition
      override def execute(context: Context, toolCall: ToolCall): IO[ToolResult] =
        IO.raiseError(new AssertionError("tool execution should not be invoked"))
    }

    given LlmClient[IO] with {
      override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
        IO.pure(LLMResponse("plain answer", List.empty))
    }

    import DefaultInputProcessor.given

    val context = Context(List(CommandTool.definition), Memory(), List.empty)
    val session = Session("session_1", context, Path.of("target/test-session.md"))
    val state = ChatState(Vector.empty, "hello")

    val result = summon[InputProcessor[IO]]
      .process("hello", state, session, _ => IO.unit)
      .unsafeRunSync()
      .get

    val (nextState, nextSession) = result
    val working = nextSession.context.memory.working.get

    assertEquals(nextState.history.map(_.text), Vector("hello", "plain answer"))
    assertEquals(nextSession.context.history.map(_.output), List("plain answer"))
    assertEquals(working.candidateAnswer, Some("plain answer"))
    assertEquals(working.messages.map(_.role), List("user", "assistant"))
    assertEquals(working.messages.last.content, Some("plain answer"))
  }
}
