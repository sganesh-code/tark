package com.tark.adapters.context

import cats.effect.IO
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.ports.outbound.context.SessionRepository

import java.nio.file.{Files, Path}

class FileSessionRepository extends SessionRepository[IO] {
  override def loadLatestMemory(directory: Path): IO[Memory] = IO.blocking {
    if (Files.exists(directory) && Files.isDirectory(directory)) {
      val stream = Files.list(directory)
      try {
        val files = stream.toArray.map(_.asInstanceOf[Path])
          .filter(p => p.toString.endsWith(".md") && p.getFileName.toString.startsWith("session_"))
        if (files.nonEmpty) {
          val latestFile = files.maxBy(p => Files.getLastModifiedTime(p).toMillis)
          val content = Files.readString(latestFile)
          Context.deserialize(content).getOrElse(Memory())
        } else {
          Memory()
        }
      } finally {
        stream.close()
      }
    } else {
      Memory()
    }
  }
}

object FileSessionRepository {
  given default: FileSessionRepository = new FileSessionRepository
}
