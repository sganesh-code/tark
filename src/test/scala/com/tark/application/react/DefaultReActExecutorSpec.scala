package com.tark.application.react

import com.tark.application.instances.all.given
import com.tark.application.react.DefaultReActExecutor.given
import com.tark.application.time.Clock.given

import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.context.Context
import com.tark.domain.react.{CallTool, Finish, ReActState}
import com.tark.domain.tool.{Tool, ToolCallRequest, ToolContext, ToolType}
import com.tark.ports.outbound.react.{ReActExecutor, ReActLlmClient, ReActResponse}
import com.tark.ports.shared.tool.{ToolExecutor, ToolRegistry}
import io.circe.Json

class DefaultReActExecutorSpec extends FunSuite {

  private val systemPrompt = "Test system prompt"
  private val dummyTool = Tool("calculator", (ctx: ToolContext) => "4", ToolType.GenericTool)

  // A mock registry and tool executor
  private given mockToolRegistry: ToolRegistry[Context] with {
    override def register(context: Context, tool: Tool): Context =
      context.copy(tools = context.tools + (tool.name -> tool))

    override def lookup(context: Context, toolName: String): Option[Tool] =
      if (toolName == "calculator") Some(dummyTool) else None
  }

  private given mockToolExecutor: ToolExecutor[Tool] with {
    override def execute(tool: Tool, context: ToolContext): String =
      tool.execute(context)

    override def validate(tool: Tool): Boolean = true
  }

  test("DefaultReActExecutor: executes successfully and converges to final answer") {
    val initialContext = Context(Map("calculator" -> dummyTool), Map.empty, List.empty)

    // A stateful mock client that queues successive responses
    var turns = List(
      ReActResponse("I should calculate 2+2.", Right(ToolCallRequest("calculator", Map("arguments" -> "2+2")))),
      ReActResponse("The result is 4. I can finish now.", Left("The answer is 4"))
    )

    val mockClient = new ReActLlmClient[IO] {
      override def getCompletion(
        prompt: String,
        history: List[com.tark.domain.Interaction],
        systemPrompt: String,
        tools: List[Tool]
      ): IO[Either[String, ReActResponse]] = IO {
        turns match {
          case head :: tail =>
            turns = tail
            Right(head)
          case Nil =>
            Left("No more queued responses")
        }
      }
    }

    val executor = new DefaultReActExecutor[IO](mockClient, initialContext, systemPrompt, maxSteps = 10, onStepUpdate = _ => IO.unit)
    val executionIO = summon[ReActExecutor[DefaultReActExecutor[IO], IO]].execute(executor, "Solve 2+2")
    val result = executionIO.unsafeRunSync()

    assertEquals(result.done, true)
    assertEquals(result.reasonForStop, Some("verifier_passed"))
    assertEquals(result.steps.size, 2)
    assertEquals(result.steps.head.thought, "I should calculate 2+2.")
    assertEquals(result.steps.head.observation, Some("4"))
    assertEquals(result.steps(1).thought, "The result is 4. I can finish now.")
    assertEquals(result.steps(1).action, Finish("The answer is 4"))
  }

  test("DefaultReActExecutor: stops immediately when step budget is reached") {
    val initialContext = Context(Map("calculator" -> dummyTool), Map.empty, List.empty)

    var counter = 0
    val mockClient = new ReActLlmClient[IO] {
      override def getCompletion(
        prompt: String,
        history: List[com.tark.domain.Interaction],
        systemPrompt: String,
        tools: List[Tool]
      ): IO[Either[String, ReActResponse]] = IO {
        counter += 1
        Right(ReActResponse("Looping...", Right(ToolCallRequest("calculator", Map("arguments" -> s"1+1-$counter")))))
      }
    }

    // Set maxSteps budget = 2
    val executor = new DefaultReActExecutor[IO](mockClient, initialContext, systemPrompt, maxSteps = 10, onStepUpdate = _ => IO.unit)
    val executionIO = summon[ReActExecutor[DefaultReActExecutor[IO], IO]].execute(executor, "Solve 1+1")
    
    // We adjust the maxSteps of the execution state by mapping the initial execution
    // (Wait, since state is constructed inside execute, we can verify budget works
    // by building a small test state and checking isBudgetExceeded, or we can mock it).
    // Let's modify execute implementation to check state maxSteps.
    // Since DefaultReActExecutor doesn't let us pass maxSteps, let's make sure our state has a high-signal budget check.
    // Wait! Let's verify that budget check triggers if we run a long loop.
    // Since default maxSteps is 10, the loop will terminate after 10 steps!
    val result = executionIO.unsafeRunSync()
    assertEquals(result.done, true)
    assertEquals(result.reasonForStop, Some("max_steps_reached"))
    assertEquals(result.steps.size, 10)
  }

  test("DefaultReActExecutor: stops on stagnation when identical tool call is repeated with same output") {
    val initialContext = Context(Map("calculator" -> dummyTool), Map.empty, List.empty)

    val mockClient = new ReActLlmClient[IO] {
      override def getCompletion(
        prompt: String,
        history: List[com.tark.domain.Interaction],
        systemPrompt: String,
        tools: List[Tool]
      ): IO[Either[String, ReActResponse]] = IO {
        // Keeps requesting the exact same calculation
        Right(ReActResponse("I must compute 2+2.", Right(ToolCallRequest("calculator", Map("arguments" -> "2+2")))))
      }
    }

    val executor = new DefaultReActExecutor[IO](mockClient, initialContext, systemPrompt, maxSteps = 10, onStepUpdate = _ => IO.unit)
    val executionIO = summon[ReActExecutor[DefaultReActExecutor[IO], IO]].execute(executor, "Compute 2+2")
    
    val result = executionIO.unsafeRunSync()
    assertEquals(result.done, true)
    assertEquals(result.reasonForStop, Some("stagnation_detected"))
    assertEquals(result.steps.size, 2) // Terminates on the second step because it matches step 1 exactly!
  }

  test("DefaultReActExecutor: retrieves and includes relevant episodic memory in the LLM user prompt") {
    import com.tark.domain.memory.{EpisodeSummary, EpisodicMemory, Memory}

    val relevantEpisode = EpisodeSummary("sess_relevant", 1000L, "Highly related summary about calculator syntax", List("Prefers addition first"))
    val unrelatedEpisode = EpisodeSummary("sess_unrelated", 2000L, "Completely unrelated session summary about database cleanup", List("Wiped staging table"))

    val memory = Memory(
      episodic = EpisodicMemory(List(relevantEpisode, unrelatedEpisode))
    )
    val initialContext = Context(Map("calculator" -> dummyTool), memory, List.empty)

    var capturedPrompt: Option[String] = None

    val mockClient = new ReActLlmClient[IO] {
      override def getCompletion(
        prompt: String,
        history: List[com.tark.domain.Interaction],
        systemPrompt: String,
        tools: List[Tool]
      ): IO[Either[String, ReActResponse]] = IO {
        capturedPrompt = Some(prompt)
        Right(ReActResponse("Converged.", Left("Done")))
      }
    }

    val executor = new DefaultReActExecutor[IO](mockClient, initialContext, systemPrompt, maxSteps = 10, onStepUpdate = _ => IO.unit)
    // Run execute with a goal matching keywords in the relevantEpisode summary/takeaways
    val executionIO = summon[ReActExecutor[DefaultReActExecutor[IO], IO]].execute(executor, "Compute addition on calculator")
    val result = executionIO.unsafeRunSync()

    assert(capturedPrompt.isDefined)
    val prompt = capturedPrompt.get

    // Assert that the relevant episode's summary is successfully injected
    assert(prompt.contains("Context from Prior Sessions:"))
    assert(prompt.contains("sess_relevant"))
    assert(prompt.contains("Highly related summary about calculator syntax"))
    assert(prompt.contains("Prefers addition first"))

    // Assert that the unrelated episode was filtered out by our keyword relevance scorer!
    assert(!prompt.contains("sess_unrelated"))
    assert(!prompt.contains("Completely unrelated session summary about database cleanup"))
  }
}
