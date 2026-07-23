package com.tark.application.backend

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.context.{Context, Session}
import com.tark.domain.tool.{OpenAIMessage, OpenAIUsage, ToolCall, ToolCallFunction}
import com.tark.domain.{AgentState, Config, GoalContract, ProgressContext}
import com.tark.ports.AgentBackend
import com.tark.ports.outbound.backend.*
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.tool.{CommandExecutor, ToolCallExecutor}
import com.tark.ports.outbound.tool.ToolCallExecutor.given
import com.tark.ports.shared.serialization.Sink
import com.tark.ui.{AgentAction, AgentTask}
import fs2.Stream

import java.nio.file.Path

final class DefaultAgentBackend[F[_]: Sync] private (
  sessionRef: Ref[F, Session],
  updateCompletionsRef: Ref[F, List[String] => F[Unit]],
  usageRef: Ref[F, OpenAIUsage],
  reactEngine: ReActLoopEngine[F],
  goalContractParser: GoalContractParser[F],
  taskPlanner: TaskPlanner[F, GoalContract],
  planVerifier: PlanVerifier[F, GoalContract],
  progressTracker: ProgressTracker[F],
  contextDistiller: ContextDistiller[F],
  config: Config
)(using
  sink: Sink[F, Context, Path],
  summarizer: EpisodicMemorySummarizer[F],
  commandExecutor: CommandExecutor[F],
  clock: Clock[F]
) extends AgentBackend[F] {
  
  private val slashCommandBackend =
    SlashCommandBackend[F](actions => emitActions(actions*), sessionRef, commandExecutor, clock, updateCompletionsRef)

  override def registerCompletions(update: List[String] => F[Unit]): F[Unit] =
    slashCommandBackend.registerCompletions(update)

  override def handleInput(input: String): Stream[F, AgentTask[F]] = {
    val clean = input.trim
    if clean.isEmpty then Stream.empty
    else if clean.startsWith("/") then slashCommandBackend.handleInput(clean)
    else processPrompt(input)
  }

  private def processPrompt(input: String): Stream[F, AgentTask[F]] =
    Stream.eval(sessionRef.get).flatMap { session =>
      val hasGoal = session.context.memory.working.exists(_.goal.nonEmpty)

      if (hasGoal) {
        runStandardConversation(session, input)
      } else {
        // Create an instant intake task so the spinner starts immediately!
        val intakeTask = AgentTask(
          description = Some("[Intake] Analyzing goal and generating plan"),
          action = Stream.eval(goalContractParser.parseGoal(input)).flatMap { contract =>
            Stream.eval(taskPlanner.generatePlan(contract)).flatMap { plan =>
              Stream.eval(planVerifier.verifyPlan(contract, plan)).flatMap { isValid =>
                val planWorkflowStream = if (isValid) {
                  Stream.emit((plan, List.empty[String]))
                } else {
                  Stream.eval(taskPlanner.generatePlan(contract)).map { refined =>
                    (refined, List(s"[Intake] [WARNING] Plan failed verification. Executed plan-refinement pass."))
                  }
                }

                planWorkflowStream.flatMap { case (finalPlan, warnings) =>
                  val updatedContext = session.context.updateAgentState(state =>
                    state.withGoalContract(contract).withPlan(finalPlan).copy(currentStep = 0)
                  )
                  val updatedSession = session.copy(context = updatedContext)

                  val contractMessages = List(
                    s"[Intake] Goal established: ${contract.goal}",
                    s"[Intake] Deliverable: ${contract.deliverable}"
                  ) ++ Option.when(contract.constraints.nonEmpty)(s"[Intake] Constraints: ${contract.constraints.mkString(", ")}")

                  val planMessages = if (finalPlan.nonEmpty) {
                    s"[Intake] Generated Plan:" :: finalPlan.map(step => s"  * $step")
                  } else List("[Intake] No plan generated.")

                  val notifyWarnings = Stream.emits(warnings.map(AgentAction.SystemMessage[F]))
                  val notifyContract = Stream.emits(contractMessages.map(AgentAction.SystemMessage[F]))
                  val notifyPlan = Stream.emits(planMessages.map(AgentAction.SystemMessage[F]))

                  val persist = Stream.eval(
                    sink.write(updatedContext, session.sessionPath) >> sessionRef.set(updatedSession)
                  ).drain

                  notifyWarnings ++ notifyContract ++ notifyPlan ++ persist ++
                    runStandardConversation(updatedSession, input).flatMap(_.action)
                }
              }
            }
          }
        )
        Stream.emit(intakeTask)
      }
    }

  private def pruneHistory(messages: List[OpenAIMessage]): List[OpenAIMessage] = {
    val (systemMsgs, nonSystemMsgs) = messages.partition(_.role == "system")
    val maxHistoryMessages = 8
    val keptHistory = if (nonSystemMsgs.length > maxHistoryMessages) {
      nonSystemMsgs.takeRight(maxHistoryMessages)
    } else {
      nonSystemMsgs
    }
    systemMsgs ++ keptHistory
  }

  private def runStandardConversation(session: Session, input: String): Stream[F, AgentTask[F]] = {
    val userMessage = OpenAIMessage(role = "user", content = Some(input))
    val history = historyMessages(session.context)
    val prunedHistory = pruneHistory(history)
    val initialMessages = prunedHistory :+ userMessage

    Stream.eval(Ref.of[F, Option[ConversationResult]](None)).flatMap { resultRef =>
      reactEngine.runConversation(session.context, initialMessages, depth = 0, Vector.empty, resultRef) ++
        Stream.emit(persistConversationTask(session, resultRef))
    }
  }

  private[backend] def persistConversationTask(session: Session, resultRef: Ref[F, Option[ConversationResult]]): AgentTask[F] =
    AgentTask(
      description = Some("Persisting session"),
      action =
        Stream.eval(resultRef.get).flatMap {
          case Some(result) =>
            Stream.eval(updateContextAfterConversation(session.context, result)).flatMap { case (updatedContext, progressionMsgOpt) =>
              val updatedSession = session.copy(context = updatedContext)
              val writeAndSet = Stream.eval(sink.write(updatedContext, session.sessionPath) >> sessionRef.set(updatedSession)).drain
              val notification = progressionMsgOpt match {
                case Some(msg) => Stream.emit(AgentAction.SystemMessage[F](msg))
                case None => Stream.empty
              }
              writeAndSet ++ notification
            }
          case None =>
            Stream.empty
        }
    )

  private def emitActions(actions: AgentAction[F]*): Stream[F, AgentTask[F]] =
    Stream.emit(AgentTask(description = None, action = Stream.emits(actions.toVector)))

  private def historyMessages(context: Context): List[OpenAIMessage] =
    context.memory.working.flatMap(_.messages.some.filter(_.nonEmpty)).getOrElse {
      context.history.flatMap { interaction =>
        List(
          OpenAIMessage(role = "user", content = Some(interaction.input)),
          OpenAIMessage(role = "assistant", content = Some(interaction.output))
        )
      }
    }

  private def shouldDistill(input: String, output: String): Boolean = {
    val lowerIn = input.toLowerCase
    // Do not distill if we are explicitly reading files or viewing source code
    !lowerIn.contains("cat ") && 
    !lowerIn.contains("less ") && 
    !lowerIn.contains("more ") && 
    !lowerIn.contains("tail ") && 
    !lowerIn.contains("head ") && 
    !lowerIn.contains("grep ") && 
    !lowerIn.contains("find ")
  }

  private def updateContextAfterConversation(context: Context, result: ConversationResult): F[(Context, Option[String])] = {
    val currentAgentState = context.memory.working.getOrElse(AgentState())
    val F = summon[Sync[F]]

    val isMaxToolCallsExceeded = result.finalAnswer.exists(_.contains("Maximum tool call"))

    // 1. Evaluate task step progression (skip if limit was exceeded)
    val activeStepOpt = currentAgentState.plan.lift(currentAgentState.currentStep)
    val progressionF = if (isMaxToolCallsExceeded) {
      F.pure((currentAgentState.currentStep, currentAgentState.completedSteps, None))
    } else {
      activeStepOpt match {
        case Some(activeStep) =>
          val progressCtx = ProgressContext(currentAgentState.goal, activeStep, result.messages)
          progressTracker.evaluateProgress(progressCtx).map { completed =>
            if (completed) {
              val nextStep = currentAgentState.currentStep + 1
              val updatedCompleted = currentAgentState.completedSteps :+ activeStep
              (nextStep, updatedCompleted, Some(s"[Progression] Checked off: $activeStep"))
            } else {
              (currentAgentState.currentStep, currentAgentState.completedSteps, None)
            }
          }
        case None =>
          F.pure((currentAgentState.currentStep, currentAgentState.completedSteps, None))
      }
    }

    // 2. Perform budget-aware, de-duplicated distillation
    val totalChars = result.messages.flatMap(_.content).map(_.length).sum
    val estimatedTokens = totalChars / 4
    val pressureThreshold = (config.contextWindowSize * 0.75).toInt

    val shouldTriggerDistillation = config.enableDistillation && !isMaxToolCallsExceeded && (estimatedTokens > pressureThreshold)

    for {
      (nextStep, nextCompleted, progressionMsgOpt) <- progressionF
      (distilledMessages, distilledInteractions) <- if (shouldTriggerDistillation) {
        // Group by tool_call_id to ensure we only distill unique tool outputs once
        val uniqueToolMessages = result.messages.filter { msg =>
          val associatedToolCall = result.messages.flatMap(_.tool_calls.getOrElse(Nil)).find(_.id == msg.tool_call_id.getOrElse(""))
          val inputArgs = associatedToolCall.map(_.function.arguments).getOrElse("")
          msg.role == "tool" && msg.content.exists(_.length > config.distillationThreshold) && shouldDistill(inputArgs, msg.content.getOrElse(""))
        }.groupBy(_.tool_call_id.getOrElse("")).map(_._2.head).toList

        uniqueToolMessages.traverse { msg =>
          val associatedToolCall = result.messages.flatMap(_.tool_calls.getOrElse(Nil)).find(_.id == msg.tool_call_id.getOrElse(""))
          val toolCall = associatedToolCall.getOrElse(ToolCall(msg.tool_call_id.getOrElse(""), "function", ToolCallFunction("unknown", "")))
          contextDistiller.distill(context, toolCall, msg.content.get).map { distilled =>
            msg.tool_call_id.getOrElse("") -> distilled
          }
        }.map(_.toMap).map { distillationMap =>
          val messages = result.messages.map { msg =>
            if (msg.role == "tool") {
              val toolId = msg.tool_call_id.getOrElse("")
              distillationMap.get(toolId) match {
                case Some(distilled) => msg.copy(content = Some(distilled))
                case None => msg
              }
            } else msg
          }
          val interactions = result.interactions.map { interaction =>
            distillationMap.get(interaction.id) match {
              case Some(distilled) => interaction.copy(output = distilled)
              case None => interaction
            }
          }
          (messages, interactions)
        }
      } else {
        F.pure((result.messages, result.interactions))
      }
      isAllDone = currentAgentState.plan.nonEmpty && nextStep >= currentAgentState.plan.length
      nextAgentState = currentAgentState.copy(
        messages = distilledMessages,
        candidateAnswer = result.finalAnswer,
        plan = currentAgentState.plan,
        currentStep = nextStep,
        completedSteps = nextCompleted,
        done = isAllDone,
        reasonForStop = if (isAllDone) Some("assistant_response") else result.finalAnswer.map(_ => "assistant_response")
      )
    } yield (
      context.copy(
        history = context.history ++ distilledInteractions,
        memory = context.memory.copy(working = Some(nextAgentState))
      ),
      progressionMsgOpt
    )
  }
}

object DefaultAgentBackend {

  def create[F[_]: Sync](session: Session)(using
    sink: Sink[F, Context, Path],
    summarizer: EpisodicMemorySummarizer[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F],
    llmClient: LlmClient[F],
    streamingLlmClient: StreamingLlmClient[F],
    goalContractParser: GoalContractParser[F],
    taskPlanner: TaskPlanner[F, GoalContract],
    planVerifier: PlanVerifier[F, GoalContract],
    progressTracker: ProgressTracker[F],
    mcpRegistry: com.tark.ports.outbound.mcp.McpRegistry[F],
    config: Config
  ): F[DefaultAgentBackend[F]] =
    for {
      sessionRef <- Ref.of[F, Session](session)
      updateRef <- Ref.of[F, List[String] => F[Unit]](_ => Sync[F].unit)
      usageRef <- Ref.of[F, OpenAIUsage](OpenAIUsage(0, 0, 0))
      streamingHandler = StreamingResponseHandler[F](streamingLlmClient, llmClient, usageRef, config)
      toolCallExecutor = summon[ToolCallExecutor[F, com.tark.domain.tool.ToolDefinition]]
      contextDistiller = ContextDistiller[F](llmClient)
      reactEngine = ReActLoopEngine[F](streamingHandler, toolCallExecutor, clock, config)
    } yield DefaultAgentBackend(sessionRef, updateRef, usageRef, reactEngine, goalContractParser, taskPlanner, planVerifier, progressTracker, contextDistiller, config)

  def createWithFallbackStreaming[F[_]: Sync](session: Session)(using
    sink: Sink[F, Context, Path],
    summarizer: EpisodicMemorySummarizer[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F],
    llmClient: LlmClient[F],
    goalContractParser: GoalContractParser[F],
    taskPlanner: TaskPlanner[F, GoalContract],
    planVerifier: PlanVerifier[F, GoalContract],
    progressTracker: ProgressTracker[F],
    mcpRegistry: com.tark.ports.outbound.mcp.McpRegistry[F],
    config: Config
  ): F[DefaultAgentBackend[F]] = {
    given StreamingLlmClient[F] = StreamingLlmClient.fromBuffered(llmClient)
    create[F](session)
  }
}
