package com.tark.domain

import com.tark.application.instances.all.given

import com.tark.domain.context.Context
import com.tark.domain.memory.{EpisodeSummary, EpisodicMemory, Memory, ProceduralMemory, Skill}
import com.tark.ports.outbound.context.ContextOps
import munit.FunSuite

class MemorySpec extends FunSuite {

  test("Memory: initializes with correct defaults") {
    val memory = Memory()
    assert(memory.working.isEmpty)
    assertEquals(memory.episodic.episodes, List.empty)
    assertEquals(memory.procedural.skills, List.empty)
    assertEquals(memory.semantic, None)
    assertEquals(memory.legacy, Map.empty)
    assert(memory.isEmpty)
  }

  test("Memory: can update working memory (AgentState)") {
    val memory = Memory()
    val updatedState = AgentState(goal = "Analyze Memory")
    val updated = memory.copy(working = Some(updatedState))

    assertEquals(updated.working.map(_.goal), Some("Analyze Memory"))
    assert(!updated.isEmpty)
  }

  test("Memory: can add episodic summaries") {
    val memory = Memory()
    val episode = EpisodeSummary("sess_123", 123456789L, "Completed first test", List("Prefers simple CSS", "Encountered timeout"))
    val updated = memory.copy(episodic = EpisodicMemory(List(episode)))

    assertEquals(updated.episodic.episodes.length, 1)
    assertEquals(updated.episodic.episodes.head.sessionId, "sess_123")
    assertEquals(updated.episodic.episodes.head.keyTakeaways, List("Prefers simple CSS", "Encountered timeout"))
  }

  test("Memory: can add procedural skills") {
    val memory = Memory()
    val skill = Skill("FileSearch", "Search for files in dir", List("find .", "grep pattern"))
    val updated = memory.copy(procedural = ProceduralMemory(List(skill)))

    assertEquals(updated.procedural.skills.length, 1)
    assertEquals(updated.procedural.skills.head.name, "FileSearch")
    assertEquals(updated.procedural.skills.head.steps, List("find .", "grep pattern"))
  }

  test("Memory: supports legacy Map-style operations") {
    val memory = Memory()
    val updated = memory + ("key1" -> "val1") + ("key2" -> "val2")

    assertEquals(updated.get("key1"), Some("val1"))
    assertEquals(updated.get("key2"), Some("val2"))
    assertEquals(updated.get("missing"), None)
    assertEquals(updated.legacy, Map("key1" -> "val1", "key2" -> "val2"))
  }

  test("ContextOps: getMemory and updateMemory perform pure updates") {
    val initialContext = Context(Map.empty, Memory(), List.empty)
    val ops = summon[ContextOps[Context]]

    val initialMemory = ops.getMemory(initialContext)
    assert(initialMemory.isEmpty)

    val updatedContext = ops.updateMemory(initialContext, mem => {
      val episode = EpisodeSummary("s1", 1000L, "Done X")
      mem.copy(
        episodic = EpisodicMemory(List(episode)),
        procedural = ProceduralMemory(List(Skill("S", "D", List("step1"))))
      )
    })

    val updatedMemory = ops.getMemory(updatedContext)
    assertEquals(updatedMemory.episodic.episodes.length, 1)
    assertEquals(updatedMemory.episodic.episodes.head.sessionId, "s1")
    assertEquals(updatedMemory.procedural.skills.head.name, "S")

    // Verify immutability of the original context
    assert(ops.getMemory(initialContext).isEmpty)
  }
}
