package com.tark.ports.shared.serialization

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.application.instances.all.given
import com.tark.domain.Interaction
import com.tark.domain.context.Context
import com.tark.domain.tool.{Tool, ToolType}
import munit.FunSuite

import java.nio.file.Files

class SinkSpec extends FunSuite {
  test("Sink: serializes and writes Context using Serializable to markdown file and in-memory StringBuilder") {
    val tool = Tool("command_ls", _ => "mock", ToolType.CommandTool)
    val interaction = Interaction("id1", "ls", "file1", 123456L, "command_ls")
    val context = Context(
      tools = Map("command_ls" -> tool),
      memory = Map("key1" -> "val1"),
      history = List(interaction)
    )

    val serializer = summon[Serializable[Context, String]]
    val serialized = serializer.serialize(context)

    assert(serialized.contains("# Session Context"))
    assert(serialized.contains("command_ls"))
    assert(serialized.contains("key1"))
    assert(serialized.contains("val1"))
    assert(serialized.contains("Interaction 1"))

    val tempFile = Files.createTempFile("tark-session-test", ".md")
    tempFile.toFile.deleteOnExit()
    val fileSink = summon[Sink[IO, Context, java.nio.file.Path]]
    fileSink.write(context, tempFile).unsafeRunSync()

    val fileContent = Files.readString(tempFile)
    assertEquals(fileContent, serialized)

    val stringBuilder = new java.lang.StringBuilder()
    val memSink = summon[Sink[IO, Context, java.lang.StringBuilder]]
    memSink.write(context, stringBuilder).unsafeRunSync()

    assertEquals(stringBuilder.toString, serialized)
  }
}
