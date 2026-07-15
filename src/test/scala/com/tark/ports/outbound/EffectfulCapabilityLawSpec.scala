package com.tark.ports.outbound

import com.tark.application.instances.all.given

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import com.tark.domain.context.{Context, Session}
import com.tark.domain.react.ReActState
import com.tark.domain.sandbox.Sandbox
import com.tark.domain.ui.{Cell, Screen}
import com.tark.ports.outbound.context.SessionProvider
import com.tark.ports.outbound.sandbox.SandboxManager
import com.tark.ports.outbound.trace.TraceWriter
import com.tark.ports.outbound.ui.{ScreenWriter, ScreenWriterF}
import com.tark.ports.shared.react.ReActTraceSerializer
import com.tark.ports.shared.serialization.{Serializable, Sink}
import munit.FunSuite

import java.io.{PrintWriter, StringWriter}
import java.nio.file.Path

class EffectfulCapabilityLawSpec extends FunSuite {

  case class TestSandbox(name: String) extends Sandbox

  test("Sink laws: composite sink is equivalent to serialize then write and sequencing overwrites destination") {
    val builder = new java.lang.StringBuilder()
    val context = Context(Map.empty, Map("key" -> "value"), List.empty)
    val serializer = summon[Serializable[Context, String]]
    val contextSink = summon[Sink[IO, Context, java.lang.StringBuilder]]
    val stringSink = summon[Sink[IO, String, java.lang.StringBuilder]]

    contextSink.write(context, builder).unsafeRunSync()
    val compositeOutput = builder.toString

    stringSink.write(serializer.serialize(context), builder).unsafeRunSync()
    val explicitOutput = builder.toString

    assertEquals(compositeOutput, explicitOutput)

    stringSink.write("replacement", builder).unsafeRunSync()
    assertEquals(builder.toString, "replacement")
  }

  test("TraceWriter laws: writes serialized ReAct state through F") {
    var captured: Option[String] = None
    val writer = new TraceWriter[IO] {
      override def writeTrace(reactState: ReActState, session: Session): IO[Unit] = IO {
        captured = Some(ReActTraceSerializer.serialize(reactState))
      }
    }
    val state = ReActState("goal")
    val session = Session("session", Context(Map.empty, Map.empty, List.empty), Path.of("target/test-sessions/session.md"))

    writer.writeTrace(state, session).unsafeRunSync()

    assert(captured.exists(_.contains("# ReAct Execution Trace")))
    assert(captured.exists(_.contains("goal")))
  }

  test("SessionProvider resource laws: acquire and release happen once per use") {
    var acquired = 0
    var released = 0
    val session = Session("session", Context(Map.empty, Map.empty, List.empty), Path.of("target/test-sessions/session.md"))
    val provider = new SessionProvider[IO] {
      override def createSession: Resource[IO, Session] =
        Resource.make(IO { acquired += 1; session })(_ => IO { released += 1 })
    }

    val result = provider.createSession.use(s => IO.pure(s.id)).unsafeRunSync()

    assertEquals(result, "session")
    assertEquals(acquired, 1)
    assertEquals(released, 1)
  }

  test("SandboxManager lifecycle laws: start precedes execute and stop follows use") {
    var events = Vector.empty[String]
    val sandbox = TestSandbox("test")
    val manager = new SandboxManager[IO, TestSandbox] {
      override def start(sandbox: TestSandbox): IO[Unit] = IO {
        events = events :+ s"start:${sandbox.name}"
      }

      override def execute(sandbox: TestSandbox, command: String): IO[String] = IO {
        events = events :+ s"execute:$command"
        "ok"
      }

      override def stop(sandbox: TestSandbox): IO[Unit] = IO {
        events = events :+ s"stop:${sandbox.name}"
      }
    }

    val program = Resource.make(manager.start(sandbox).as(sandbox))(manager.stop).use { s =>
      manager.execute(s, "cmd")
    }

    assertEquals(program.unsafeRunSync(), "ok")
    assertEquals(events, Vector("start:test", "execute:cmd", "stop:test"))
  }

  test("ScreenWriterF laws: existing IO ScreenWriter instances can be lifted without changing output") {
    val screen = Screen(1, 1)
    screen.put(0, 0, Cell("A"))

    val screenWriter = new ScreenWriter[Screen] {
      override def write(screen: Screen, out: PrintWriter): IO[Unit] = IO {
        out.print(screen.cell(0, 0).glyph)
      }
    }
    val lifted = ScreenWriterF.fromScreenWriter(screenWriter)

    val directOut = new StringWriter()
    val liftedOut = new StringWriter()

    screenWriter.write(screen, new PrintWriter(directOut)).unsafeRunSync()
    lifted.write(screen, new PrintWriter(liftedOut)).unsafeRunSync()

    assertEquals(liftedOut.toString, directOut.toString)
  }
}
