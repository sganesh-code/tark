package com.tark.ports.outbound.memory

import com.tark.domain.Interaction
import com.tark.domain.memory.EpisodeSummary

/**
 * A service that distills session history (interactions) into structured episode summaries
 * with the help of an LLM.
 */
trait EpisodicMemorySummarizer[F[_]] {
  
  /**
   * Summarizes a list of interactions from a session, returning an EpisodeSummary
   * containing a high-level summary and extracted key takeaways (e.g., user preferences or failures).
   */
  def summarize(sessionId: String, history: List[Interaction]): F[EpisodeSummary]
}
