package com.tark.application.react

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.application.time.Clock
import com.tark.domain.*
import com.tark.domain.context.Context
import com.tark.domain.react.{CallTool, Finish, ReActState, ReActStep}
import com.tark.domain.tool.{Tool, ToolContext}
import com.tark.ports.outbound.memory.MemoryOps
import com.tark.ports.outbound.react.{ReActExecutor, ReActLlmClient}
import com.tark.ports.shared.react.{ReActPrompt, ReActStateOps}
import com.tark.ports.shared.tool.{ToToolDescription, ToolExecutor, ToolRegistry, ToolValidator}
import io.circe.syntax.*

class DefaultReActExecutor[F[_]](
  val client: ReActLlmClient[F],
  val context: Context,
  val systemPrompt: String,
  val maxSteps: Int,
  val onStepUpdate: ReActState => F[Unit]
)(using
  val toolRegistry: ToolRegistry[Context],
  val toolExecutor: ToolExecutor[Tool],
  val sync: Sync[F],
  val clock: Clock[F]
)

object DefaultReActExecutor {

  /**
   * Stagnation detector: returns true if the last two actions and their observations are identical.
   */
  private def isStagnant(state: ReActState): Boolean = {
    if (state.steps.size >= 2) {
      val last = state.steps.last
      val prev = state.steps(state.steps.size - 2)
      last.action == prev.action && last.observation == prev.observation
    } else {
      false
    }
  }

  given default[F[_]: Sync](using
    toolRegistry: ToolRegistry[Context],
    toolExecutor: ToolExecutor[Tool],
    clock: Clock[F]
  ): ReActExecutor[DefaultReActExecutor[F], F] with {
    override def execute(executor: DefaultReActExecutor[F], goal: String): F[ReActState] = {
      val stateOps = summon[ReActStateOps[ReActState]]
      val initialState = ReActState(goal, maxSteps = executor.maxSteps)
      val F = summon[Sync[F]]

      def runLoop(state: ReActState): F[ReActState] = {
        if (state.done) {
          F.pure(state)
        } else if (stateOps.isBudgetExceeded(state)) {
          F.pure(stateOps.markDone(state, "max_steps_reached"))
        } else if (isStagnant(state)) {
          F.pure(stateOps.markDone(state, "stagnation_detected"))
        } else {
          val relevantEpisodes = MemoryOps.retrieveRelevantEpisodes(executor.context.memory, goal)
          val userPrompt = ReActPrompt.userPrompt(state, relevantEpisodes)
          val toolsList = executor.context.tools.values.toList

          executor.client.getCompletion(userPrompt, executor.context.history, executor.systemPrompt, toolsList).flatMap {
            case Left(err) =>
              F.pure(stateOps.markDone(state, s"error: $err"))

            case Right(response) =>
              val thought = response.thought
              response.action match {
                case Left(finalAnswer) =>
                  val step = ReActStep(thought, Finish(finalAnswer), Some(finalAnswer))
                  val nextState = stateOps.addStep(state, step)
                  for {
                    _ <- executor.onStepUpdate(nextState)
                  } yield stateOps.markDone(nextState, "verifier_passed")

                case Right(toolCall) =>
                  executor.toolRegistry.lookup(executor.context, toolCall.toolName) match {
                    case None =>
                      val errorObs = s"Error: Tool '${toolCall.toolName}' was not found in ToolRegistry."
                      val step = ReActStep(thought, CallTool(toolCall.toolName, toolCall.args.asJson), Some(errorObs))
                      val nextState = stateOps.addStep(state, step)
                      for {
                        _ <- executor.onStepUpdate(nextState)
                        res <- runLoop(nextState)
                      } yield res

                    case Some(tool) =>
                      val descOps = summon[ToToolDescription[Tool]]
                      val definition = descOps.describe(tool)
                      val inputJson = toolCall.args.asJson

                      ToolValidator.validate(definition, inputJson) match {
                        case Left(validationError) =>
                          val errorObs = s"Error: Validation failed. $validationError"
                          val step = ReActStep(thought, CallTool(toolCall.toolName, inputJson), Some(errorObs))
                          val nextState = stateOps.addStep(state, step)
                          for {
                            _ <- executor.onStepUpdate(nextState)
                            res <- runLoop(nextState)
                          } yield res

                        case Right(_) =>
                          for {
                            now <- executor.clock.realTimeMillis
                            toolCtx = ToolContext(executor.context, toolCall.args, s"exec_$now")
                            toolOutput <- F.blocking(executor.toolExecutor.execute(tool, toolCtx))

                            step = ReActStep(thought, CallTool(toolCall.toolName, inputJson), Some(toolOutput))
                            nextState = stateOps.addStep(state, step)
                            _ <- executor.onStepUpdate(nextState)
                            finalState <- runLoop(nextState)
                          } yield finalState
                      }
                  }
              }
          }
        }
      }

      runLoop(initialState)
    }
  }
}
