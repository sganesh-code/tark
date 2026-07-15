package com.tark.ports.inbound.tool

import cats.MonadThrow
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.context.{Context, Session}
import com.tark.domain.memory.EpisodeSummary
import com.tark.ports.shared.serialization.Sink
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer

import java.nio.file.Path

object SessionMemoryTransitions {
  def summarizeAndPersist[F[_]: MonadThrow](
    session: Session,
    fallbackReason: String,
    transform: Context => Context,
    persistWhenHistoryEmpty: Boolean
  )(using summarizer: EpisodicMemorySummarizer[F], sink: Sink[F, Context, Path], clock: Clock[F]): F[Context] = {
    val F = summon[MonadThrow[F]]

    def appendEpisode(context: Context, summary: EpisodeSummary): Context = {
      val updatedMemory = context.memory.copy(
        episodic = context.memory.episodic.copy(
          episodes = context.memory.episodic.episodes :+ summary
        )
      )
      context.copy(memory = updatedMemory)
    }

    val summarized =
      if (session.context.history.nonEmpty) {
        summarizer
          .summarize(session.id, session.context.history)
          .map(summary => appendEpisode(session.context, summary))
          .handleErrorWith { err =>
            clock.realTimeMillis.map { now =>
              val fallbackSummary = EpisodeSummary(
                sessionId = session.id,
                timestamp = now,
                summary = s"$fallbackReason: ${err.getMessage}",
                keyTakeaways = List.empty
              )
              appendEpisode(session.context, fallbackSummary)
            }
          }
      } else {
        F.pure(session.context)
      }

    summarized.flatMap { context =>
      val updatedContext = transform(context)
      if (session.context.history.nonEmpty || persistWhenHistoryEmpty) {
        sink.write(updatedContext, session.sessionPath).as(updatedContext)
      } else {
        F.pure(updatedContext)
      }
    }
  }
}
