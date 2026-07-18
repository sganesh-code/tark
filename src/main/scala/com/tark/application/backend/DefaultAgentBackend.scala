package com.tark.application.backend

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.AgentState
import com.tark.domain.Interaction
import com.tark.domain.context.{Context, Session}
import com.tark.domain.tool.{OpenAIMessage, OpenAIUsage, ToolCall, ToolCallFunction, ToolResult}
import com.tark.ports.AgentBackend
import com.tark.ports.inbound.tool.SessionMemoryTransitions
import com.tark.ports.outbound.backend.{LLMResponse, LlmClient, LlmStreamEvent, Prompt, StreamingLlmClient, ToolCallAccumulator, GroundedPrompt}
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.tool.CommandExecutor
import com.tark.ports.shared.serialization.Sink
import com.tark.ui.{AgentAction, AgentTask}
import fs2.Stream
import io.circe.syntax.*

import java.nio.file.Path

final class DefaultAgentBackend[F[_]: Sync] private (
  sessionRef: Ref[F, Session],
  updateCompletionsRef: Ref[F, List[String] => F[Unit]],
  usageRef: Ref[F, OpenAIUsage]
)(using
  sink: Sink[F, Context, Path],
  summarizer: EpisodicMemorySummarizer[F],
  clock: Clock[F],
  commandExecutor: CommandExecutor[F],
  llmClient: LlmClient[F],
  streamingLlmClient: StreamingLlmClient[F]
) extends AgentBackend[F] {

  import DefaultAgentBackend.*

  override def registerCompletions(update: List[String] => F[Unit]): F[Unit] =
    updateCompletionsRef.set(update) >> update(DefaultCompletions)

  override def handleInput(input: String): Stream[F, AgentTask[F]] = {
    val clean = input.trim
    if clean.isEmpty then Stream.empty
    else if clean.startsWith("/") then processSlashCommand(clean)
    else processPrompt(input)
  }

  private def processSlashCommand(command: String): Stream[F, AgentTask[F]] = {
    val name = command.split("\\s+", 2).headOption.getOrElse(command)
    name match {
      case "/help" =>
        emitActions(
          AgentAction.SystemMessage("Available commands: /help, /clear, /memory, /run <command>, /exit")
        )

      case "/memory" =>
        Stream.emit(
          AgentTask(
            description = None,
            action = Stream.eval(sessionRef.get.map(session => AgentAction.SystemMessage(renderMemory(session.context))): F[AgentAction[F]])
          )
        )

      case "/run" =>
        val rawCommand = command.stripPrefix("/run").trim
        Stream.emit(
          AgentTask(
            description = if rawCommand.nonEmpty then Some(s"Executing command: $rawCommand") else None,
            action = Stream.eval(runCommand(rawCommand)).flatMap(actions => Stream.emits(actions))
          )
        )

      case "/clear" =>
        Stream.emit(
          AgentTask(
            description = Some("Summarizing session history and clearing"),
            action = Stream.eval(clearSession()).flatMap(actions => Stream.emits(actions))
          )
        )

      case "/exit" =>
        Stream.emit(
          AgentTask(
            description = Some("Summarizing session history"),
            action = Stream.eval(exitSession()).flatMap(actions => Stream.emits(actions))
          )
        )

      case _ =>
        emitActions(AgentAction.SystemMessage(s"Unknown command: $command. Type /help for assistance."))
    }
  }

  private def processPrompt(input: String): Stream[F, AgentTask[F]] =
    Stream.eval(sessionRef.get).flatMap { session =>
      val userMessage = OpenAIMessage(role = "user", content = Some(input))
      val initialMessages = historyMessages(session.context) :+ userMessage

      Stream.eval(Ref.of[F, Option[ConversationResult]](None)).flatMap { resultRef =>
        runConversation(session.context, initialMessages, depth = 0, Vector.empty, resultRef) ++
          Stream.emit(persistConversationTask(session, resultRef))
      }
    }

  private def runCommand(rawCommand: String): F[Vector[AgentAction[F]]] =
    if rawCommand.isEmpty then
      Sync[F].pure(Vector(AgentAction.SystemMessage("Error: No command provided to run. Usage: /run <command>")))
    else
      sessionRef.get.flatMap { session =>
        session.context.tools.find(_.function.name == "command_executor") match {
          case Some(_) =>
            for {
              now <- clock.realTimeMillis
              toolCall = ToolCall(
                id = s"exec_$now",
                `type` = "function",
                function = ToolCallFunction("command_executor", Map("command" -> rawCommand).asJson.noSpaces)
              )
              result <- commandExecutor.execute(session.context, toolCall)
              interaction = Interaction(
                id = s"interaction_$now",
                input = s"/run $rawCommand",
                output = result.content,
                timestamp = now,
                toolName = "command_executor"
              )
              updatedContext = session.context.copy(history = session.context.history :+ interaction)
              updatedSession = session.copy(context = updatedContext)
              _ <- sink.write(updatedContext, session.sessionPath)
              _ <- sessionRef.set(updatedSession)
            } yield Vector(AgentAction.Log(s"[EXECUTING] -> $rawCommand"), AgentAction.SystemMessage(result.content))

          case None =>
            Sync[F].pure(Vector(AgentAction.SystemMessage("Error: command_executor tool is not registered.")))
        }
      }

  private def clearSession(): F[Vector[AgentAction[F]]] =
    sessionRef.get.flatMap { session =>
      SessionMemoryTransitions
        .summarizeAndPersist(
          session = session,
          fallbackReason = "Cleared with summarization error",
          transform = _.copy(history = List.empty),
          persistWhenHistoryEmpty = true
        )
        .flatMap { updatedContext =>
          sessionRef.set(session.copy(context = updatedContext)).as {
            Vector(
              AgentAction.ClearScreen(),
              AgentAction.SystemMessage("Session cleared.")
            )
          }
        }
    }

  private def exitSession(): F[Vector[AgentAction[F]]] =
    sessionRef.get.flatMap { session =>
      val persist =
        if session.context.history.nonEmpty then
          SessionMemoryTransitions.summarizeAndPersist(
            session = session,
            fallbackReason = "Exited with summarization error",
            transform = identity,
            persistWhenHistoryEmpty = false
          )
        else Sync[F].pure(session.context)

      persist.flatMap(context => sessionRef.set(session.copy(context = context))).as(Vector(AgentAction.Exit()))
    }

  private def runConversation(
    context: Context,
    messages: List[OpenAIMessage],
    depth: Int,
    accumulatedInteractions: Vector[Interaction],
    resultRef: Ref[F, Option[ConversationResult]]
  ): Stream[F, AgentTask[F]] =
    if depth >= MaxToolDepth then
      val message = "Error: Maximum tool call execution depth exceeded."
      val result = ConversationResult(
        messages = messages,
        interactions = accumulatedInteractions,
        finalAnswer = Some(message)
      )
      Stream.emit(
        AgentTask(
          description = Some("Stopping conversation"),
          action = Stream.eval(resultRef.set(Some(result))).drain ++ Stream.emit(AgentAction.SystemMessage(message))
        )
      )
    else
      Stream.eval(Ref.of[F, Option[LLMResponse[ToolCall]]](None)).flatMap { responseRef =>
        val basePrompt = Prompt(messages, context.tools)
        val goalQuery = context.memory.working.map(_.goal).filter(_.nonEmpty).getOrElse(originalUserInput(messages))
        val groundedPrompt = GroundedPrompt.compile(basePrompt, context.memory, goalQuery)

        val responseTask = AgentTask(
          description = Some(if depth == 0 then "Waiting for assistant response" else "Waiting for assistant response after tool results"),
          action =
            collectStreamingResponse(groundedPrompt, responseRef)
        )

        Stream.emit(responseTask) ++
          Stream.eval(responseRef.get.flatMap {
            case Some(response) => Sync[F].pure(response)
            case None           => Sync[F].raiseError(new IllegalStateException("Assistant response task did not complete."))
          }).flatMap { response =>
            val assistantMessage = OpenAIMessage(
              role = "assistant",
              content = response.content.some.filter(_.nonEmpty),
              tool_calls = response.results.some.filter(_.nonEmpty)
            )

            if response.results.isEmpty then
              Stream.emit(finalizeConversationTask(messages :+ assistantMessage, accumulatedInteractions, response, resultRef))
            else
              Stream.eval(Ref.of[F, Vector[(ToolCall, ToolResult)]](Vector.empty)).flatMap { toolResultRef =>
                val toolTasks = response.results.map { toolCall =>
                  AgentTask(
                    description = Some(s"Executing tool: ${toolCall.function.name}"),
                    action =
                      Stream.emit(AgentAction.Log(s"Executing tool: ${toolCall.function.name}")) ++
                        Stream
                          .eval(executeTool(context)(toolCall).flatTap(result => toolResultRef.update(_ :+ (toolCall, result))))
                          .map(result => AgentAction.SystemMessage(result.content))
                  )
                }

                Stream.emits(toolTasks) ++
                  Stream.eval(toolResultRef.get).flatMap { toolResults =>
                    val toolMessages = toolResults.map { case (toolCall, result) =>
                      OpenAIMessage(
                        role = "tool",
                        content = Some(result.content),
                        tool_call_id = Some(toolCall.id)
                      )
                    }.toList

                    Stream.eval(clock.realTimeMillis).flatMap { now =>
                      val interactions = toolResults.zipWithIndex.map { case ((toolCall, result), idx) =>
                        Interaction(
                          id = s"interaction_${now}_$idx",
                          input = toolCall.function.arguments,
                          output = result.content,
                          timestamp = now + idx,
                          toolName = toolCall.function.name
                        )
                      }

                      runConversation(
                        context,
                        messages ++ List(assistantMessage) ++ toolMessages,
                        depth + 1,
                        accumulatedInteractions ++ interactions,
                        resultRef
                      )
                    }
                  }
              }
          }
      }

  private def finalizeConversationTask(
    messages: List[OpenAIMessage],
    accumulatedInteractions: Vector[Interaction],
    response: LLMResponse[ToolCall],
    resultRef: Ref[F, Option[ConversationResult]]
  ): AgentTask[F] =
    AgentTask(
      description = Some("Finalizing assistant response"),
      action =
        Stream.eval {
          clock.realTimeMillis.flatMap { now =>
            val interaction = Interaction(
              id = s"interaction_$now",
              input = originalUserInput(messages),
              output = response.content,
              timestamp = now,
              toolName = "llm_completion"
            )
            resultRef.set(
              Some(
                ConversationResult(
                  messages = messages,
                  interactions = accumulatedInteractions :+ interaction,
                  finalAnswer = Some(response.content)
                )
              )
            )
          }
        }.drain
    )

  private def persistConversationTask(session: Session, resultRef: Ref[F, Option[ConversationResult]]): AgentTask[F] =
    AgentTask(
      description = Some("Persisting session"),
      action =
        Stream.eval {
          resultRef.get.flatMap {
            case Some(result) =>
              val updatedContext = updateContextAfterConversation(session.context, result)
              val updatedSession = session.copy(context = updatedContext)
              sink.write(updatedContext, session.sessionPath) >> sessionRef.set(updatedSession)
            case None =>
              Sync[F].unit
          }
        }.drain
    )

  private def executeTool(context: Context)(toolCall: ToolCall): F[ToolResult] =
    if toolCall.function.name == "command_executor" then commandExecutor.execute(context, toolCall)
    else Sync[F].pure(ToolResult(s"Tool '${toolCall.function.name}' is not available."))

  private def emitActions(actions: AgentAction[F]*): Stream[F, AgentTask[F]] =
    Stream.emit(AgentTask(description = None, action = Stream.emits(actions.toVector)))

  private def responseToActions(response: LLMResponse[ToolCall]): Vector[AgentAction[F]] =
    Option(response.content)
      .filter(_.nonEmpty)
      .map(content => Vector(AgentAction.Log(content)))
      .getOrElse(Vector.empty)

  private def updateUsageAndGetStatus(usage: OpenAIUsage): F[String] =
    usageRef.updateAndGet(curr =>
      OpenAIUsage(
        curr.prompt_tokens + usage.prompt_tokens,
        curr.completion_tokens + usage.completion_tokens,
        curr.total_tokens + usage.total_tokens
      )
    ).map(u => s"LLM Usage: Prompt ${u.prompt_tokens} | Completion ${u.completion_tokens} | Total ${u.total_tokens}")

  private def collectStreamingResponse(
    prompt: Prompt,
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]]
  ): Stream[F, AgentAction[F]] = {
    val responseStream = Stream
      .eval(Ref.of[F, StreamingResponseState](StreamingResponseState.empty))
      .flatMap { stateRef =>
        streamingLlmClient
          .chatStream(prompt)
          .evalMapAccumulate(Vector.empty[AgentAction[F]]) { case (_, event) =>
            handleStreamEvent(prompt, event, stateRef, responseRef).map(actions => actions -> actions)
          }
          .flatMap { case (_, actions) => Stream.emits(actions) }
          .handleErrorWith { error =>
            fallbackBufferedResponse(prompt, responseRef, s"Streaming failed: ${error.getMessage}. Falling back to buffered response.")
          }
      }

    responseStream ++ Stream.eval(responseRef.get).flatMap {
      case Some(response) =>
        Stream.eval(updateUsageAndGetStatus(response.usage)).flatMap { statusText =>
          Stream.emit(AgentAction.StatusUpdate(statusText))
        }
      case None =>
        Stream.empty
    }
  }

  private def handleStreamEvent(
    prompt: Prompt,
    event: LlmStreamEvent,
    stateRef: Ref[F, StreamingResponseState],
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]]
  ): F[Vector[AgentAction[F]]] =
    event match {
      case LlmStreamEvent.ContentDelta(text) =>
        stateRef.update(_.appendContent(text)).as(Vector(AgentAction.AssistantDelta(text)))

      case LlmStreamEvent.ThinkingDelta(_) =>
        Sync[F].pure(Vector.empty)

      case delta: LlmStreamEvent.ToolCallDelta =>
        stateRef.update(_.appendToolDelta(delta)).as(Vector.empty)

      case LlmStreamEvent.Usage(usage) =>
        stateRef.update(_.withUsage(usage)).as(Vector.empty)

      case LlmStreamEvent.Completed(Some(response)) =>
        responseRef.set(Some(response)).as(Vector(AgentAction.AssistantEnd()))

      case LlmStreamEvent.Completed(None) =>
        stateRef.get.flatMap { state =>
          state.toResponse match {
            case Right(response) =>
              responseRef.set(Some(response)).as(Vector(AgentAction.AssistantEnd()))
            case Left(errors) =>
              val message = errors.mkString(" ")
              val response = LLMResponse(s"Streaming tool call failed: $message", List.empty[ToolCall], OpenAIUsage(0, 0, 0))
              responseRef.set(Some(response)).as(Vector(AgentAction.AssistantEnd(), AgentAction.SystemMessage(response.content)))
          }
        }

      case LlmStreamEvent.Failed(message) =>
        fallbackBufferedResponseAsList(
          prompt = prompt,
          responseRef = responseRef,
          warning = s"Streaming failed: $message. Falling back to buffered response."
        )
    }

  private def fallbackBufferedResponse(
    prompt: Prompt,
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]],
    warning: String
  ): Stream[F, AgentAction[F]] =
    Stream.emit(AgentAction.SystemMessage(warning)) ++
      Stream.eval(llmClient.chat(prompt).flatTap(response => responseRef.set(Some(response)))).flatMap(response => Stream.emits(responseToActions(response)))

  private def fallbackBufferedResponseAsList(
    prompt: Prompt,
    responseRef: Ref[F, Option[LLMResponse[ToolCall]]],
    warning: String
  ): F[Vector[AgentAction[F]]] =
    llmClient
      .chat(prompt)
      .flatTap(response => responseRef.set(Some(response)))
      .map(response => Vector(AgentAction.SystemMessage(warning)) ++ responseToActions(response))

  private def historyMessages(context: Context): List[OpenAIMessage] =
    context.memory.working.flatMap(_.messages.some.filter(_.nonEmpty)).getOrElse {
      context.history.flatMap { interaction =>
        List(
          OpenAIMessage(role = "user", content = Some(interaction.input)),
          OpenAIMessage(role = "assistant", content = Some(interaction.output))
        )
      }
    }

  private def updateContextAfterConversation(context: Context, result: ConversationResult): Context = {
    val currentAgentState = context.memory.working.getOrElse(AgentState())
    val nextAgentState = currentAgentState.copy(
      messages = result.messages,
      candidateAnswer = result.finalAnswer,
      done = result.finalAnswer.isDefined,
      reasonForStop = result.finalAnswer.map(_ => "assistant_response")
    )
    context.copy(
      history = context.history ++ result.interactions,
      memory = context.memory.copy(working = Some(nextAgentState))
    )
  }

  private def renderMemory(context: Context): String = {
    val mem = context.memory
    val sb = new java.lang.StringBuilder()
    sb.append("[Memory Layers Status]\n")

    sb.append("  * Working Memory: ")
    mem.working match {
      case Some(w) =>
        sb.append(s"Active (Goal = '${w.goal}', Done = ${w.done})\n")
        if w.constraints.nonEmpty then sb.append(s"    - Constraints: ${w.constraints.mkString(", ")}\n")
        if w.plan.nonEmpty then sb.append(s"    - Plan Steps: ${w.plan.size} steps\n")
      case None =>
        sb.append("Inactive\n")
    }

    sb.append(s"  * Episodic Memory: ${mem.episodic.episodes.size} episodes\n")
    mem.episodic.episodes.zipWithIndex.foreach { case (ep, idx) =>
      sb.append(s"    [$idx] Session '${ep.sessionId}': ${ep.summary}\n")
      if ep.keyTakeaways.nonEmpty then sb.append(s"         Takeaways: ${ep.keyTakeaways.mkString(", ")}\n")
    }

    sb.append(s"  * Procedural Memory: ${context.tools.size} tools, ${mem.procedural.skills.size} skills\n")
    if context.tools.nonEmpty then
      sb.append(s"    - Registered Tools: ${context.tools.map(_.function.name).mkString(", ")}\n")
    mem.procedural.skills.foreach { skill =>
      sb.append(s"    - Skill '${skill.name}': ${skill.description}\n")
    }

    sb.toString.trim
  }

  private def originalUserInput(messages: List[OpenAIMessage]): String =
    messages.find(_.role == "user").flatMap(_.content).getOrElse("")
}

object DefaultAgentBackend {
  private val MaxToolDepth = 10
  val DefaultCompletions: List[String] = List("/help", "/clear", "/memory", "/run", "/exit")

  private final case class ConversationResult(
    messages: List[OpenAIMessage],
    interactions: Vector[Interaction],
    finalAnswer: Option[String]
  )

  def create[F[_]: Sync](session: Session)(using
    sink: Sink[F, Context, Path],
    summarizer: EpisodicMemorySummarizer[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F],
    llmClient: LlmClient[F],
    streamingLlmClient: StreamingLlmClient[F]
  ): F[DefaultAgentBackend[F]] =
    for {
      sessionRef <- Ref.of[F, Session](session)
      updateRef <- Ref.of[F, List[String] => F[Unit]](_ => Sync[F].unit)
      usageRef <- Ref.of[F, OpenAIUsage](OpenAIUsage(0, 0, 0))
    } yield DefaultAgentBackend(sessionRef, updateRef, usageRef)

  def createWithFallbackStreaming[F[_]: Sync](session: Session)(using
    sink: Sink[F, Context, Path],
    summarizer: EpisodicMemorySummarizer[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F],
    llmClient: LlmClient[F]
  ): F[DefaultAgentBackend[F]] = {
    given StreamingLlmClient[F] = StreamingLlmClient.fromBuffered(llmClient)
    create[F](session)
  }

  private final case class StreamingResponseState(
    content: String,
    toolCalls: ToolCallAccumulator,
    usage: OpenAIUsage = OpenAIUsage(0, 0, 0)
  ) {
    def appendContent(delta: String): StreamingResponseState =
      copy(content = content + delta)

    def appendToolDelta(delta: LlmStreamEvent.ToolCallDelta): StreamingResponseState =
      copy(toolCalls = toolCalls.add(delta))

    def withUsage(u: OpenAIUsage): StreamingResponseState =
      copy(usage = u)

    def toResponse: Either[List[String], LLMResponse[ToolCall]] =
      toolCalls.complete.map(calls => LLMResponse(content, calls, usage))
  }

  private object StreamingResponseState {
    val empty: StreamingResponseState = StreamingResponseState("", ToolCallAccumulator.empty)
  }
}
