package com.tark.domain

import com.tark.application.instances.all.given

import com.tark.domain.context.Context
import com.tark.domain.memory.{EpisodeSummary, EpisodicMemory, Memory, ProceduralMemory, SemanticMemory, Skill}
import munit.FunSuite
import com.tark.ports.shared.serialization.Serializable

class MemorySerializationSpec extends FunSuite {

  test("Memory Serialization: full round-trip serializes and deserializes Context.memory perfectly") {
    // 1. Construct a complex, nested Memory object
    val workingState = AgentState(
      goal = "Serialize everything",
      deliverable = "Passing tests",
      constraints = List("Must be fast", "Must be pure"),
      assumptions = List("Test env is clean"),
      knownFacts = List("MUnit is configured"),
      openQuestions = List("Is nested deserialization robust?"),
      plan = List("Serialize", "Deserialize", "Compare"),
      currentStep = 1,
      completedSteps = List("Serialize"),
      toolResults = List("Step 1 succeeded"),
      candidateAnswer = Some("In progress"),
      confidence = 0.9,
      done = false,
      reasonForStop = None
    )

    val episode = EpisodeSummary(
      sessionId = "session_abc",
      timestamp = 1783944800L,
      summary = "Initial research phase.",
      keyTakeaways = List("Prioritize safety", "Skip Git on no-repo")
    )

    val skill = Skill(
      name = "SummarizeHistory",
      description = "Condenses chat history",
      steps = List("Collect", "Send to LLM", "Parse")
    )

    val originalMemory = Memory(
      working = Some(workingState),
      episodic = EpisodicMemory(List(episode)),
      procedural = ProceduralMemory(List(skill)),
      semantic = Some(SemanticMemory(List("Fact A", "Fact B")))
    )

    val context = Context(
      tools = List.empty,
      memory = originalMemory,
      history = List.empty
    )

    // 2. Serialize Context to markdown string
    val serializer = summon[Serializable[Context, String]]
    val serializedMarkdown = serializer.serialize(context)

    // Verify human-readable headings are present
    assert(serializedMarkdown.contains("# Session Context"))
    assert(serializedMarkdown.contains("## Memory"))
    assert(serializedMarkdown.contains("### Episodic Memory"))
    assert(serializedMarkdown.contains("session_abc"))
    assert(serializedMarkdown.contains("### Procedural Memory"))
    assert(serializedMarkdown.contains("SummarizeHistory"))
    assert(serializedMarkdown.contains("## Agent State"))
    assert(serializedMarkdown.contains("Serialize everything"))

    // Verify machine-readable JSON is embedded
    assert(serializedMarkdown.contains("<!-- MEMORY_JSON"))
    assert(serializedMarkdown.contains("-->"))

    // 3. Deserialize back using Context.deserialize
    val deserializedResult = Context.deserialize(serializedMarkdown)

    assert(deserializedResult.isRight)
    val deserializedMemory = deserializedResult.toOption.get

    // 4. Assert perfect structural equivalence!
    assertEquals(deserializedMemory.working, originalMemory.working)
    assertEquals(deserializedMemory.episodic, originalMemory.episodic)
    assertEquals(deserializedMemory.procedural, originalMemory.procedural)
    assertEquals(deserializedMemory.semantic, originalMemory.semantic)
    assertEquals(deserializedMemory, originalMemory)
  }

  test("Memory Serialization: deserialize fails gracefully on malformed or missing MEMORY_JSON block") {
    val result1 = Context.deserialize("# Context\nSome raw text without comments")
    assert(result1.isLeft)
    assertEquals(result1.left.toOption.get, "MEMORY_JSON comment not found in markdown.")

    val result2 = Context.deserialize("# Context\n<!-- MEMORY_JSON\n{invalid json\n")
    assert(result2.isLeft)
    assertEquals(result2.left.toOption.get, "MEMORY_JSON comment not closed properly.")

    val result3 = Context.deserialize("# Context\n<!-- MEMORY_JSON\n{invalid json}\n-->")
    assert(result3.isLeft)
    assert(result3.left.toOption.get.contains("expected"))
  }

  test("Config: serializes and deserializes correctly via Circe") {
    import io.circe.parser._
    import io.circe.syntax._

    val original = Config(
      modelId = "custom-model",
      maxTokens = 1024,
      baseUrl = "http://custom-ollama:11434/v1/chat/completions",
      sandboxImageName = "custom-sandbox:latest"
    )

    val jsonStr = original.asJson.noSpaces
    val decodeResult = decode[Config](jsonStr)

    assert(decodeResult.isRight)
    assertEquals(decodeResult.toOption.get, original)
  }

  test("Config: exposes pure defaults without reading environment") {
    val config = Config.default
    assertEquals(config.modelId, Config.DefaultModelId)
    assertEquals(config.maxTokens, Config.DefaultMaxTokens)
    assertEquals(config.baseUrl, Config.DefaultBaseUrl)
    assertEquals(config.sandboxImageName, Config.DefaultSandboxImageName)
  }
}
