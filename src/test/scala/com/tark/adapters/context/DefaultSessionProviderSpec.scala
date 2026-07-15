package com.tark.adapters.context

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.adapters.context.DefaultSessionProvider.given
import com.tark.adapters.sandbox.docker.DockerSandbox
import com.tark.application.instances.all.given
import com.tark.domain.context.Context
import com.tark.domain.memory.{EpisodicMemory, Memory}
import com.tark.ports.outbound.context.SessionProvider
import com.tark.ports.outbound.sandbox.SandboxManager
import com.tark.ports.shared.serialization.{Serializable, Sink}
import munit.FunSuite

import java.nio.file.{Files, Path}

class DefaultSessionProviderSpec extends FunSuite {
  class InMemorySink extends Sink[IO, Context, Path] {
    var lastSaved: Option[Context] = None
    override def write(data: Context, destination: Path): IO[Unit] = IO {
      lastSaved = Some(data)
    }.void
  }

  test("SessionProvider: correctly initializes session, builds/starts sandbox and stops on cleanup") {
    class MockSandboxManager extends SandboxManager[IO, DockerSandbox] {
      var started: List[DockerSandbox] = List.empty
      var stopped: List[DockerSandbox] = List.empty
      override def start(sandbox: DockerSandbox): IO[Unit] = IO {
        started = started :+ sandbox
      }
      override def execute(sandbox: DockerSandbox, command: String): IO[String] = IO.pure("")
      override def stop(sandbox: DockerSandbox): IO[Unit] = IO {
        stopped = stopped :+ sandbox
      }
    }

    given mockSandboxManager: MockSandboxManager = new MockSandboxManager()
    given sink: InMemorySink = new InMemorySink()

    val provider = summon[SessionProvider[IO]]
    val (session, release) = provider.createSession.allocated.unsafeRunSync()

    try {
      assert(session.id.startsWith("session_"))
      assert(session.sessionPath.toString.endsWith(".md"))
      assert(session.context.tools.contains("command_executor"))

      val sandbox = session.context.sandbox.get.asInstanceOf[DockerSandbox]
      assertEquals(mockSandboxManager.started.head, sandbox)

      assert(sink.lastSaved.isDefined)
      assert(sink.lastSaved.get.tools.contains("command_executor"))
    } finally {
      release.unsafeRunSync()
    }

    val sandbox = session.context.sandbox.get.asInstanceOf[DockerSandbox]
    assertEquals(mockSandboxManager.stopped.head, sandbox)
  }

  test("SessionProvider: correctly resumes cumulative episodic memories from the latest session file on startup") {
    class MockSandboxManager extends SandboxManager[IO, DockerSandbox] {
      override def start(sandbox: DockerSandbox): IO[Unit] = IO.unit
      override def execute(sandbox: DockerSandbox, command: String): IO[String] = IO.pure("")
      override def stop(sandbox: DockerSandbox): IO[Unit] = IO.unit
    }

    given mockSandboxManager: MockSandboxManager = new MockSandboxManager()
    given sink: InMemorySink = new InMemorySink()

    val testSessionsDir = Path.of("target/sessions")
    Files.createDirectories(testSessionsDir)
    val priorSessionPath = testSessionsDir.resolve("session_mock_prior_test.md")

    val episode = com.tark.domain.memory.EpisodeSummary("sess_old", 500L, "Prior learnings summary", List("Fact A"))
    val priorMemory = Memory(
      episodic = EpisodicMemory(List(episode))
    )
    val priorContext = Context(Map.empty, priorMemory, List.empty)

    val serializedContext = summon[Serializable[Context, String]].serialize(priorContext)
    Files.writeString(priorSessionPath, serializedContext)

    try {
      val provider = summon[SessionProvider[IO]]
      val (session, release) = provider.createSession.allocated.unsafeRunSync()

      try {
        val loadedEpisodes = session.context.memory.episodic.episodes
        assertEquals(loadedEpisodes.size, 1)
        assertEquals(loadedEpisodes.head.sessionId, "sess_old")
        assertEquals(loadedEpisodes.head.summary, "Prior learnings summary")
      } finally {
        release.unsafeRunSync()
      }
    } finally {
      Files.deleteIfExists(priorSessionPath)
    }
  }
}
