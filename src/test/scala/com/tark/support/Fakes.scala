package com.tark.support

import cats.effect.{IO, Resource}
import com.tark.domain.Interaction
import com.tark.domain.context.Session
import com.tark.domain.react.ReActState
import com.tark.domain.sandbox.Sandbox
import com.tark.domain.tool.{Tool, ToolCallRequest}
import com.tark.ports.outbound.backend.LlmClient
import com.tark.ports.outbound.context.SessionProvider
import com.tark.ports.outbound.react.{ReActLlmClient, ReActResponse}
import com.tark.ports.outbound.sandbox.SandboxManager
import com.tark.ports.outbound.trace.TraceWriter
import com.tark.ports.shared.serialization.Sink

object Fakes {
  final class RecordingSink[A, D] extends Sink[IO, A, D] {
    var writes: Vector[(A, D)] = Vector.empty

    override def write(data: A, destination: D): IO[Unit] = IO {
      writes = writes :+ (data -> destination)
    }
  }

  final class StaticLlmClient(response: Either[String, List[ToolCallRequest]]) extends LlmClient[IO] {
    var prompts: Vector[(String, List[Interaction], String, List[Tool])] = Vector.empty

    override def getCompletion(
      prompt: String,
      history: List[Interaction],
      systemPrompt: String,
      tools: List[Tool]
    ): IO[Either[String, List[ToolCallRequest]]] = IO {
      prompts = prompts :+ (prompt, history, systemPrompt, tools)
      response
    }
  }

  final class StaticReActLlmClient(response: Either[String, ReActResponse]) extends ReActLlmClient[IO] {
    var prompts: Vector[(String, List[Interaction], String, List[Tool])] = Vector.empty

    override def getCompletion(
      prompt: String,
      history: List[Interaction],
      systemPrompt: String,
      tools: List[Tool]
    ): IO[Either[String, ReActResponse]] = IO {
      prompts = prompts :+ (prompt, history, systemPrompt, tools)
      response
    }
  }

  final class RecordingSandboxManager[S <: Sandbox](executeResult: String = "") extends SandboxManager[IO, S] {
    var started: Vector[S] = Vector.empty
    var executed: Vector[(S, String)] = Vector.empty
    var stopped: Vector[S] = Vector.empty

    override def start(sandbox: S): IO[Unit] = IO {
      started = started :+ sandbox
    }

    override def execute(sandbox: S, command: String): IO[String] = IO {
      executed = executed :+ (sandbox -> command)
      executeResult
    }

    override def stop(sandbox: S): IO[Unit] = IO {
      stopped = stopped :+ sandbox
    }
  }

  final class StaticSessionProvider(session: Session) extends SessionProvider[IO] {
    var released: Boolean = false

    override def createSession: Resource[IO, Session] =
      Resource.make(IO.pure(session))(_ => IO { released = true })
  }

  final class RecordingTraceWriter extends TraceWriter[IO] {
    var traces: Vector[(ReActState, Session)] = Vector.empty

    override def writeTrace(state: ReActState, session: Session): IO[Unit] = IO {
      traces = traces :+ (state -> session)
    }
  }
}
