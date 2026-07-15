package com.tark.ports.outbound.memory

import com.tark.domain.memory.{EpisodeSummary, Memory}

object MemoryOps {

  /**
   * Retrieves relevant episode summaries from Episodic Memory based on keywords in the current goal.
   * If there are no keyword matches, it falls back to the most recent summaries (up to maxEntries).
   */
  def retrieveRelevantEpisodes(memory: Memory, goal: String, maxEntries: Int = 3): List[EpisodeSummary] = {
    val episodes = memory.episodic.episodes
    if (episodes.isEmpty) {
      List.empty
    } else {
      // Split the goal into lowercase alphanumeric words, filtering out short words and common stopwords
      val stopWords = Set("the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "for", "with", "at", "by", "from", "is", "are", "was", "were", "be", "been")
      val goalKeywords = goal.toLowerCase
        .split("\\W+")
        .map(_.trim)
        .filter(word => word.length > 2 && !stopWords.contains(word))
        .toSet

      if (goalKeywords.isEmpty) {
        // Fallback to latest episodes if no keywords can be extracted
        episodes.sortBy(-_.timestamp).take(maxEntries)
      } else {
        // Score each episode based on keyword matches in its summary and takeaways
        val scored = episodes.map { ep =>
          val textToMatch = (ep.summary + " " + ep.keyTakeaways.mkString(" ")).toLowerCase
          val score = goalKeywords.count(keyword => textToMatch.contains(keyword))
          (ep, score)
        }

        // Rank by highest score, filtering out zero matches
        val matching = scored.filter(_._2 > 0).sortBy(-_._2).map(_._1)
        if (matching.nonEmpty) {
          matching.take(maxEntries)
        } else {
          // Graceful fallback to the latest episodes if no direct keyword overlap exists
          episodes.sortBy(-_.timestamp).take(maxEntries)
        }
      }
    }
  }
}
