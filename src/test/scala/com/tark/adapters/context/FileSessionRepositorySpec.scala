package com.tark.adapters.context

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.application.instances.all.given
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.ports.shared.serialization.Serializable
import munit.FunSuite

import java.nio.file.{Files, Path}

class FileSessionRepositorySpec extends FunSuite {
  test("FileSessionRepository.loadLatestMemory loads the latest session and parses it successfully") {
    val tempDir = Files.createTempDirectory("test-sessions-")
    try {
      val file1 = tempDir.resolve("session_1000.md")
      val file2 = tempDir.resolve("session_2000.md")

      val memory1 = Memory()
      val memory2 = Memory()

      val context2 = Context(List.empty, memory2, List.empty)
      val serialized = summon[Serializable[Context, String]].serialize(context2)

      Files.writeString(file1, "stale session content")
      Files.writeString(file2, serialized)

      val repo = new FileSessionRepository
      val loadedMemory = repo.loadLatestMemory(tempDir).unsafeRunSync()

      assertEquals(loadedMemory, memory2)
    } finally {
      import scala.jdk.CollectionConverters.*
      Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
    }
  }

  test("FileSessionRepository.loadLatestMemory returns empty memory if directory is empty") {
    val tempDir = Files.createTempDirectory("test-sessions-empty-")
    try {
      val repo = new FileSessionRepository
      val loadedMemory = repo.loadLatestMemory(tempDir).unsafeRunSync()
      assertEquals(loadedMemory, Memory())
    } finally {
      Files.delete(tempDir)
    }
  }
}
