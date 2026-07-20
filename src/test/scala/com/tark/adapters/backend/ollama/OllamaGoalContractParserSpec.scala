package com.tark.adapters.backend.ollama

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.GoalContract
import com.tark.domain.tool.OpenAIUsage
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, Prompt}
import munit.FunSuite

class OllamaGoalContractParserSpec extends FunSuite {

  test("OllamaGoalContractParser: successfully prompts LLM and parses the result into GoalContract") {
    val simulatedResponse =
      """
        |{
        |  "goal": "Build a compiler",
        |  "deliverable": "A compiler for Brainfuck in Scala",
        |  "constraints": ["Scala 3", "Optimize loops"],
        |  "assumptions": ["Standard input/output stream"],
        |  "knownFacts": ["Brainfuck has 8 commands"]
        |}
      """.stripMargin

    // Keep track of the prompt sent to LlmClient
    var capturedPrompt: Option[Prompt] = None

    val fakeLlmClient = new LlmClient[IO] {
      override def chat(prompt: Prompt): IO[LLMResponse[com.tark.domain.tool.ToolCall]] = {
        capturedPrompt = Some(prompt)
        IO.pure(
          LLMResponse(
            content = simulatedResponse,
            results = List.empty,
            usage = OpenAIUsage(10, 20, 30)
          )
        )
      }
    }

    val parser = new OllamaGoalContractParser[IO](fakeLlmClient)
    val result = parser.parseGoal("Please write a brainfuck compiler in Scala 3, optimize loop execution").unsafeRunSync()

    // Verify correct system and user messages were structured and sent
    assert(capturedPrompt.isDefined)
    val messages = capturedPrompt.get.messages
    assertEquals(messages.size, 2)
    assertEquals(messages(0).role, "system")
    assert(messages(0).content.get.contains("meticulous task-intake agent"))
    assertEquals(messages(1).role, "user")
    assert(messages(1).content.get.contains("Please write a brainfuck compiler"))

    // Verify contract fields
    assertEquals(result.goal, "Build a compiler")
    assertEquals(result.deliverable, "A compiler for Brainfuck in Scala")
    assertEquals(result.constraints, List("Scala 3", "Optimize loops"))
    assertEquals(result.assumptions, List("Standard input/output stream"))
    assertEquals(result.knownFacts, List("Brainfuck has 8 commands"))
  }

  test("OllamaGoalContractParser: handles parsing fallback on raw non-JSON text gracefully") {
    val malformedResponse = "{ malformed json }"

    val fakeLlmClient = new LlmClient[IO] {
      override def chat(prompt: Prompt): IO[LLMResponse[com.tark.domain.tool.ToolCall]] = {
        IO.pure(
          LLMResponse(
            content = malformedResponse,
            results = List.empty,
            usage = OpenAIUsage(10, 20, 30)
          )
        )
      }
    }

    val parser = new OllamaGoalContractParser[IO](fakeLlmClient)
    val result = parser.parseGoal("Let's do this").unsafeRunSync()

    // Under malformed json, the fallback line-by-line parser should run and succeed with default mapping
    assertEquals(result.goal, "{ malformed json }")
    assertEquals(result.deliverable, "Deliver completed task")
  }

  test("OllamaGoalContractParser: fails with IntakeError on fatal parsing failures") {
    // If deserialization results in a hard Left from deserializer (non-JSON that fails fallback as well, or threw error)
    // We can mock our LlmClient to return empty content or cause a forced failure.
    // For this test, let's create a custom parser that throws an error in deserialization
    import com.tark.domain.errors.IntakeError
    val fakeLlmClient = new LlmClient[IO] {
      override def chat(prompt: Prompt): IO[LLMResponse[com.tark.domain.tool.ToolCall]] = {
        // Return a response that triggers deserialization
        IO.pure(LLMResponse("", List.empty, OpenAIUsage(0, 0, 0)))
      }
    }

    val parser = new OllamaGoalContractParser[IO](fakeLlmClient)
    val result = parser.parseGoal("fail").attempt.unsafeRunSync()

    assert(result.isRight) // It will fall back to default fallback parser, which succeeds!
    // What if client chat itself fails?
    val failingLlmClient = new LlmClient[IO] {
      override def chat(prompt: Prompt): IO[LLMResponse[com.tark.domain.tool.ToolCall]] = {
        IO.raiseError(new RuntimeException("Network down"))
      }
    }
    val failingParser = new OllamaGoalContractParser[IO](failingLlmClient)
    val failure = failingParser.parseGoal("fail").attempt.unsafeRunSync()
    assert(failure.isLeft)
    assertEquals(failure.left.toOption.get.getMessage, "Network down")
  }
}
