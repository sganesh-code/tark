package com.tark.ports.outbound.trace

import com.tark.domain.react.ReActState
import com.tark.domain.context.Session
import com.tark.ports.shared.react.ReActTraceSerializer
import java.nio.file.Path
import cats.syntax.all.*

trait TraceWriter[F[_]] {
  def writeTrace(reactState: ReActState, session: Session): F[Unit]
}

object TraceWriter {
  given default[F[_]: cats.effect.Sync]: TraceWriter[F] with {
    override def writeTrace(reactState: ReActState, session: Session): F[Unit] = {
      val F = summon[cats.effect.Sync[F]]
      val traceText = ReActTraceSerializer.serialize(reactState)
      val parentDir = session.sessionPath.getParent
      val traceFileName = s"react-trace-${System.currentTimeMillis()}.md"
      val tracePath = if (parentDir != null) parentDir.resolve(traceFileName) else Path.of(traceFileName)
      F.blocking {
        val traceParent = tracePath.getParent
        if (traceParent != null && !java.nio.file.Files.exists(traceParent)) {
          java.nio.file.Files.createDirectories(traceParent)
        }
        java.nio.file.Files.writeString(tracePath, traceText)
      }.void
    }
  }
}
