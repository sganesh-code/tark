package com.tark

import cats.Monad
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.*
import io.circe.*
import sttp.client3.*
import sttp.client3.circe.*
import sttp.client3.httpclient.cats.HttpClientCatsBackend

import scala.sys.process.{Process, ProcessLogger}

object Blog {

  val availableTools: List[ToolDefinition] = List(
    ToolDefinition(
      `type` = "function",
      function = OpenAIFunction(
        name = "command_executor",
        description = "Execute any linux system commands",
        parameters = OpenAIFunctionParams.Str(
          description = "Complete command including all arguments. can also include pipes and other bash primitives"
        )
      )
    )
  )

  def main(args: Array[String]): Unit = {
    val docker = Docker(
      containerName = "tark-sandbox",
      hostPath = "./",
      containerPath = "/workspace",
      imageName = "tark-sandbox")
    HttpClientCatsBackend.resource[IO]().use { sttpBackend =>
      docker().use { sandbox =>
        Ref.of[IO, List[ReActStep]](List.empty).flatMap { traceRef =>
          val llm = TracingLLMClient(OllamaLLMClient(backend = sttpBackend), traceRef)
          val rawSandbox = summon[Sandbox[Docker, IO, ToolCall, ToolResult]]
          val tracingSandbox = TracingSandbox(rawSandbox, traceRef)
          val rawRenderer = summon[Renderer[IO, LLMResponse, ToolResult]]
          val tracingRenderer = TracingRenderer(rawRenderer, traceRef)

          AgentHarness(sandbox).run(llm, tracingSandbox, tracingRenderer)
        }
      }
    }.unsafeRunSync()
  }


  trait LLMClient[F[_] : Monad, I, A]:
    def chat(prompt: I): F[A]

  trait Renderer[F[_] : Monad, A[_], R]:
    def render(response: A[R]): F[Unit]


  final class OllamaLLMClient(
                               backend: SttpBackend[IO, Any],
                               modelName: String = "qwen3-coder:30b",
                               baseUrl: String = "http://localhost:11434/v1/chat/completions"
                             ) extends LLMClient[IO, Prompt, LLMResponse[ToolCall]] {


    override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] =
      val request = basicRequest
        .post(uri"$baseUrl")
        .body(OpenAIRequest(
          model = modelName,
          messages = prompt.messages,
          tools = prompt.availableTools
        ))
        .response(asJson[OpenAIResponse])

      backend.send(request).flatMap { response =>
        response.body match {
          case Right(successPayload) =>
            IO.pure(LLMResponse(
              content = successPayload.choices.flatMap(choice => choice.message.content).mkString("\n"),
              results = successPayload.choices.flatMap(_.message.`tool_calls`.getOrElse(Nil))))
          case Left(error) =>
            IO.pure(LLMResponse(content = s"HTTP Error running Ollama: ${error.getMessage}", results = List.empty))
        }
      }
  }

  given Renderer[IO, LLMResponse, ToolResult] with
    override def render(toolResult: LLMResponse[ToolResult]): IO[Unit] = IO.blocking {
      println("-------------------------------------------------------------")
      println(toolResult.content)
      //println("Execution results: " ++ toolResult.results.map(_.content).mkString("\n"))
      println("-------------------------------------------------------------")
    }


  trait Sandbox[Env, F[_], C, O]:
    def execute(env: Env, command: C): F[O]

  case class Prompt(
                     messages: List[OpenAIMessage],
                     availableTools: List[ToolDefinition]
                   )

  case class LLMResponse[A](
                             content: String,
                             results: List[A]
                           )

  case class ToolResult(content: String)

  extension [A](resp: LLMResponse[A]) {
    def pure[B](a: B)(using monad: cats.Monad[LLMResponse]): LLMResponse[B] = monad.pure(a)
    def flatMap[B](f: A => LLMResponse[B])(using monad: cats.Monad[LLMResponse]): LLMResponse[B] = monad.flatMap(resp)(f)
  }

  private given cats.Monad[LLMResponse] with
    override def pure[A](x: A): LLMResponse[A] = LLMResponse("", List(x))

    override def flatMap[A, B](fa: LLMResponse[A])(f: A => LLMResponse[B]): LLMResponse[B] =
      val ff: A => List[B] = a => f.apply(a).results
      fa.copy(results = fa.results.flatMap(ff))

    override def tailRecM[A, B](a: A)(f: A => LLMResponse[Either[A, B]]): LLMResponse[B] =
      val ff: A => List[Either[A, B]] = a => f(a).results
      f(a).copy(results = f(a).results.flatMap({
        case Left(a) => List()
        case Right(b) => List(b)
      }))

  sealed trait ReActAction
  case class CallTool(toolName: String, arguments: String) extends ReActAction
  case class Finish(finalAnswer: String) extends ReActAction

  case class ReActStep(
                      thought: String,
                      action: ReActAction,
                      observation: Option[String]
                      )

  case class AgentState(messages: List[OpenAIMessage])

  class AgentHarness[E](env: E):
    def run(
             llm: LLMClient[IO, Prompt, LLMResponse[ToolCall]],
             sandbox: Sandbox[E, IO, ToolCall, ToolResult],
             renderer: Renderer[IO, LLMResponse, ToolResult]
           ): IO[Unit] =

      def loop(stateRef: Ref[IO, AgentState]): IO[Unit] =
        IO.readLine.flatMap {
          case "exit" => IO.raiseError(new Exception("Exit"))
          case userInput =>
            for {
              _ <- stateRef.update(s => s.copy(messages = s.messages :+ OpenAIMessage(role = "user", content = Some(userInput))))
              _ <- runConversationStep(stateRef)
              _ <- loop(stateRef)
            } yield ()
        }

      def runConversationStep(stateRef: Ref[IO, AgentState]): IO[Unit] =
        for {
          currentState <- stateRef.get
          llmResponse <- llm.chat(Prompt(messages = currentState.messages, availableTools = availableTools))
          _ <- stateRef.update(s => s.copy(messages = s.messages :+ OpenAIMessage(
            role = "assistant",
            content = if (llmResponse.content.nonEmpty) Some(llmResponse.content) else None,
            tool_calls = if (llmResponse.results.nonEmpty) Some(llmResponse.results) else None
          )))
          _ <- if (llmResponse.results.nonEmpty) {
            for {
              toolResults <- llmResponse.results.traverse { tool =>
                sandbox.execute(env, tool).map(res => (tool.id, res))
              }
              _ <- stateRef.update { s =>
                val toolMessages = toolResults.map { case (id, res) =>
                  OpenAIMessage(role = "tool", content = Some(res.content), tool_call_id = Some(id))
                }
                s.copy(messages = s.messages ++ toolMessages)
              }
              _ <- renderer.render(LLMResponse(llmResponse.content, toolResults.map(_._2)))
              _ <- runConversationStep(stateRef)
            } yield ()
          } else {
            renderer.render(LLMResponse(llmResponse.content, List.empty))
          }
        } yield ()

      for {
        stateRef <- Ref.of[IO, AgentState](AgentState(messages = List.empty))
        _ <- loop(stateRef).handleErrorWith(_ => IO.unit)
      } yield ()


  case class Docker(containerName: String,
                    hostPath: String,
                    containerPath: String,
                    imageName: String) {

    def start(): IO[Docker] = IO.blocking {
      try {
        Process(Seq("docker", "rm", "-f", containerName)).!!
      } catch {
        case _: Exception => ""
      }

      val runCmd = Seq(
        "docker", "run", "-d",
        "--name", containerName,
        "-v", s"${hostPath}:${containerPath}",
        "-w", containerPath,
        imageName,
        "tail", "-f", "/dev/null"
      )
      Process(runCmd).!!
      this
    }
    def stop(): IO[Unit] = IO.blocking {
      try {
        Process(Seq("docker", "stop", containerName)).!!
        Process(Seq("docker", "rm", containerName)).!!
      } catch {
        case _: Exception => ""
      }
      println("Teardown of sandbox is successful")
    }
    def execute(tool: ToolCall): IO[ToolResult] = IO.blocking {
      val execCmd = Seq(
        "docker", "exec",
        containerName,
        "sh", "-c", parser.parse(tool.function.arguments) match {
          case Right(json) => json.as[Map[String, String]].map(map => map.getOrElse("command", "")) match {
            case Left(err) => "echo \"Failed to run the command\""
            case Right(value) => value
          }
          case Left(error) => "ls -ltr"
        }
      )
      val stdout = new java.lang.StringBuilder
      val stderr = new java.lang.StringBuilder
      val logger = ProcessLogger(stdout.append(_).append("\n"), stderr.append(_).append("\n"))
      val exitCode = Process(execCmd).!(logger)

      if (exitCode == 0) {
        ToolResult(content = stdout.toString.trim)
      } else {
        val err = stderr.toString.trim
        val out = stdout.toString.trim
        val errMsg = if (err.nonEmpty) err else if (out.nonEmpty) out else "Unknown error"
        throw new RuntimeException(s"Command failed with exit code $exitCode: $errMsg")
      }
    }
  }

  extension(sandbox: Docker) {
    def apply(): Resource[IO, Docker] = Resource.make { sandbox.start()} { s => s.stop() }
  }

  given Sandbox[Docker, IO, ToolCall, ToolResult] with
    override def execute(env: Docker, tool: ToolCall): IO[ToolResult] = env.execute(tool)

  // Aspect-oriented Tracing Decorators for Option B

  class TracingLLMClient(
                          underlying: LLMClient[IO, Prompt, LLMResponse[ToolCall]],
                          traceRef: Ref[IO, List[ReActStep]]
                        ) extends LLMClient[IO, Prompt, LLMResponse[ToolCall]] {
    override def chat(prompt: Prompt): IO[LLMResponse[ToolCall]] = {
      underlying.chat(prompt).flatMap { response =>
        val thought = response.content
        val step = if (response.results.nonEmpty) {
          val tool = response.results.head
          ReActStep(thought, CallTool(tool.function.name, tool.function.arguments), None)
        } else {
          ReActStep(thought, Finish(thought), None)
        }
        traceRef.update(_ :+ step).as(response)
      }
    }
  }

  class TracingSandbox[Env](
                             underlying: Sandbox[Env, IO, ToolCall, ToolResult],
                             traceRef: Ref[IO, List[ReActStep]]
                           ) extends Sandbox[Env, IO, ToolCall, ToolResult] {
    override def execute(env: Env, command: ToolCall): IO[ToolResult] = {
      underlying.execute(env, command).flatMap { result =>
        traceRef.update { steps =>
          if (steps.nonEmpty) {
            val last = steps.last
            steps.init :+ last.copy(observation = Some(result.content))
          } else {
            steps
          }
        }.as(result)
      }
    }
  }

  class TracingRenderer(
                         underlying: Renderer[IO, LLMResponse, ToolResult],
                         traceRef: Ref[IO, List[ReActStep]]
                       ) extends Renderer[IO, LLMResponse, ToolResult] {
    override def render(response: LLMResponse[ToolResult]): IO[Unit] = {
      underlying.render(response).flatMap { _ =>
        if (response.results.isEmpty) {
          for {
            trace <- traceRef.get
            _ <- printTraceLog(trace)
            _ <- traceRef.set(List.empty)
          } yield ()
        } else {
          IO.unit
        }
      }
    }
  }

  def printTraceLog(trace: List[ReActStep]): IO[Unit] = IO.blocking {
    println("\n=================== REACT TRAJECTORY TRACE ===================")
    trace.zipWithIndex.foreach { case (step, idx) =>
      println(s"[Step ${idx + 1}]")
      println(s"  Thought    : ${step.thought.trim}")
      step.action match {
        case CallTool(name, args) =>
          println(s"  Action     : CallTool('$name') with args: $args")
          println(s"  Observation: ${step.observation.getOrElse("").trim}")
        case Finish(finalAnswer) =>
          println(s"  Action     : Finish with Final Answer: ${finalAnswer.trim}")
      }
      println("-------------------------------------------------------------")
    }
    println("==============================================================\n")
  }
}

case class OpenAIMessage(
                          role: String,
                          content: Option[String] = None,
                          `tool_calls`: Option[List[ToolCall]] = None,
                          `tool_call_id`: Option[String] = None
                        )

case class OpenAPIFunctionParamsPropertiesPattern(`type`: String)

case class OpenAiFunctionParamsProperties(pattern: OpenAPIFunctionParamsPropertiesPattern)

enum OpenAIFunctionParams:
  case Str(`type`: String = "string", description: String)
  case Object(`type`: String = "object", properties: OpenAiFunctionParamsProperties, description: String)

case class OpenAIFunction(
                           name: String,
                           description: String,
                           parameters: OpenAIFunctionParams
                         )

case class ToolDefinition(
                           `type`: String,
                           function: OpenAIFunction
                         )

case class OpenAIRequest(
                          model: String,
                          messages: List[OpenAIMessage],
                          tools: List[ToolDefinition]
                        )

case class OpenAIUsage(
                        `prompt_tokens`: Int,
                        `completion_tokens`: Int,
                        `total_tokens`: Int
                      )

case class ToolCallFunction(
                             name: String,
                             arguments: String
                           )

case class ToolCall(id: String, `type`: String, function: ToolCallFunction)

case class OpenAIResponseMessage(
                                  role: String,
                                  content: Option[String] = None,
                                  `tool_calls`: Option[List[ToolCall]] = None
                                )

case class OpenAIResponseChoice(
                                 index: Int,
                                 message: OpenAIResponseMessage,
                                 `finish_reason`: String
                               )

case class OpenAIResponse(
                           id: String,
                           `object`: String,
                           created: Long,
                           model: String,
                           choices: List[OpenAIResponseChoice],
                           usage: OpenAIUsage
                         )


object OpenAPIFunctionParamsPropertiesPattern {
  given Encoder[OpenAPIFunctionParamsPropertiesPattern] = deriveEncoder

  given Decoder[OpenAPIFunctionParamsPropertiesPattern] = deriveDecoder
}

object OpenAiFunctionParamsProperties {
  given Encoder[OpenAiFunctionParamsProperties] = deriveEncoder

  given Decoder[OpenAiFunctionParamsProperties] = deriveDecoder
}

object OpenAIFunctionParams {
  given Encoder[OpenAIFunctionParams] = deriveEncoder

  given Decoder[OpenAIFunctionParams] = deriveDecoder
}

object OpenAIFunction {
  given Encoder[OpenAIFunction] = deriveEncoder

  given Decoder[OpenAIFunction] = deriveDecoder
}

object ToolCallFunction {
  given Encoder[ToolCallFunction] = deriveEncoder

  given Decoder[ToolCallFunction] = deriveDecoder
}

object ToolCall {
  given Encoder[ToolCall] = deriveEncoder

  given Decoder[ToolCall] = deriveDecoder
}

object OpenAIResponseMessage {
  given Encoder[OpenAIResponseMessage] = deriveEncoder

  given Decoder[OpenAIResponseMessage] = deriveDecoder
}

object OpenAIResponseChoice {
  given Encoder[OpenAIResponseChoice] = deriveEncoder

  given Decoder[OpenAIResponseChoice] = deriveDecoder
}

object ToolDefinition {
  given Encoder[ToolDefinition] = deriveEncoder

  given Decoder[ToolDefinition] = deriveDecoder
}

object OpenAIMessage {
  given Encoder[OpenAIMessage] = Encoder.instance { msg =>
    val base: JsonObject = JsonObject("role" -> msg.role.asJson)
    val withContent: JsonObject = msg.content.fold(base)(c => base.add("content", c.asJson))
    val withToolCalls: JsonObject = msg.tool_calls.fold(withContent)(tc => withContent.add("tool_calls", tc.asJson))
    val withToolCallId: JsonObject = msg.tool_call_id.fold(withToolCalls)(id => withToolCalls.add("tool_call_id", id.asJson))
    Json.fromJsonObject(withToolCallId)
  }

  given Decoder[OpenAIMessage] = deriveDecoder
}

object OpenAIRequest {
  given Encoder[OpenAIRequest] = deriveEncoder

  given Decoder[OpenAIRequest] = deriveDecoder
}

object OpenAIUsage {
  given Encoder[OpenAIUsage] = deriveEncoder

  given Decoder[OpenAIUsage] = deriveDecoder
}

object OpenAIResponse {
  given Encoder[OpenAIResponse] = deriveEncoder

  given Decoder[OpenAIResponse] = deriveDecoder
}

// Represent the ReAct Action algebra
sealed trait ReActAction
case class CallTool(toolName: String, arguments: String) extends ReActAction
case class Finish(finalAnswer: String) extends ReActAction

// Track an individual Reasoning + Action + Observation step
case class ReActStep(
                      thought: String,
                      action: ReActAction,
                      observation: Option[String]
                    )
