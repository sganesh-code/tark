package com.tark.application.backend

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.context.{Context, Session}
import com.tark.domain.tool.{OpenAIMessage, OpenAIUsage, ToolCall, ToolCallFunction}
import com.tark.domain.{AgentState, Config}
import com.tark.ports.AgentBackend
import com.tark.ports.outbound.backend.*
import com.tark.ports.outbound.memory.EpisodicMemorySummarizer
import com.tark.ports.outbound.tool.{CommandExecutor, DefaultToolCallExecutor}
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
        Stream.eval(goalContractParser.parseGoal(input)).flatMap { contract =>
          val updatedContext = session.context.updateAgentState(_.withGoalContract(contract))
          val updatedSession = session.copy(context = updatedContext)

          val systemMessages = List(
            s"[Intake] Goal established: ${contract.goal}",
            s"[Intake] Deliverable: ${contract.deliverable}"
          ) ++ Option.when(contract.constraints.nonEmpty)(s"[Intake] Constraints: ${contract.constraints.mkString(", ")}")

          val notificationStream = Stream.emits(systemMessages.map(msg => AgentAction.SystemMessage[F](msg)))

          val intakeTask = AgentTask(
            description = Some("Goal Intake Complete"),
            action = Stream.eval(sessionRef.set(updatedSession)).drain ++ notificationStream
          )

          Stream.emit(intakeTask) ++ runStandardConversation(updatedSession, input)
        }
      }
    }

  private def runStandardConversation(session: Session, input: String): Stream[F, AgentTask[F]] = {
    val userMessage = OpenAIMessage(role = "user", content = Some(input))
    val initialMessages = historyMessages(session.context) :+ userMessage

    Stream.eval(Ref.of[F, Option[ConversationResult]](None)).flatMap { resultRef =>
      reactEngine.runConversation(session.context, initialMessages, depth = 0, Vector.empty, resultRef) ++
        Stream.emit(persistConversationTask(session, resultRef))
    }
  }

  private[backend] def persistConversationTask(session: Session, resultRef: Ref[F, Option[ConversationResult]]): AgentTask[F] =
    AgentTask(
      description = Some("Persisting session"),
      action =
        Stream.eval {
          resultRef.get.flatMap {
            case Some(result) =>
              updateContextAfterConversation(session.context, result).flatMap { updatedContext =>
                val updatedSession = session.copy(context = updatedContext)
                sink.write(updatedContext, session.sessionPath) >> sessionRef.set(updatedSession)
              }
            case None =>
              Sync[F].unit
          }
        }.drain
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

  private def updateContextAfterConversation(context: Context, result: ConversationResult): F[Context] = {
    val currentAgentState = context.memory.working.getOrElse(AgentState())

    val distilledMessagesF = result.messages.traverse { msg =>
      if (msg.role == "tool" && config.enableDistillation && msg.content.exists(_.length > config.distillationThreshold)) {
        val toolCall = ToolCall(msg.tool_call_id.getOrElse(""), "function", ToolCallFunction("unknown", ""))
        contextDistiller.distill(context, toolCall, msg.content.get).map { distilled =>
          msg.copy(content = Some(distilled))
        }
      } else {
        Sync[F].pure(msg)
      }
    }

    val distilledInteractionsF = result.interactions.traverse { interaction =>
      if (config.enableDistillation && interaction.output.length > config.distillationThreshold) {
        val toolCall = ToolCall(interaction.id, "function", ToolCallFunction(interaction.toolName, ""))
        contextDistiller.distill(context, toolCall, interaction.output).map { distilled =>
          interaction.copy(output = distilled)
        }
      } else {
        Sync[F].pure(interaction)
      }
    }

    for {
      distilledMessages <- distilledMessagesF
      distilledInteractions <- distilledInteractionsF
      nextAgentState = currentAgentState.copy(
        messages = distilledMessages,
        candidateAnswer = result.finalAnswer,
        done = result.finalAnswer.isDefined,
        reasonForStop = result.finalAnswer.map(_ => "assistant_response")
      )
    } yield context.copy(
      history = context.history ++ distilledInteractions,
      memory = context.memory.copy(working = Some(nextAgentState))
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
    config: Config
  ): F[DefaultAgentBackend[F]] =
    for {
      sessionRef <- Ref.of[F, Session](session)
      updateRef <- Ref.of[F, List[String] => F[Unit]](_ => Sync[F].unit)
      usageRef <- Ref.of[F, OpenAIUsage](OpenAIUsage(0, 0, 0))
      streamingHandler = StreamingResponseHandler[F](streamingLlmClient, llmClient, usageRef, config)
      toolCallExecutor = DefaultToolCallExecutor[F](commandExecutor)
      contextDistiller = ContextDistiller[F](llmClient)
      reactEngine = ReActLoopEngine[F](streamingHandler, toolCallExecutor, clock, config)
    } yield DefaultAgentBackend(sessionRef, updateRef, usageRef, reactEngine, goalContractParser, contextDistiller, config)

  def createWithFallbackStreaming[F[_]: Sync](session: Session)(using
    sink: Sink[F, Context, Path],
    summarizer: EpisodicMemorySummarizer[F],
    clock: Clock[F],
    commandExecutor: CommandExecutor[F],
    llmClient: LlmClient[F],
    goalContractParser: GoalContractParser[F],
    config: Config
  ): F[DefaultAgentBackend[F]] = {
    given StreamingLlmClient[F] = StreamingLlmClient.fromBuffered(llmClient)
    create[F](session)
  }
}
