package com.tark.domain.react

import com.tark.domain.react.{ReActAction, ReActStep}
import io.circe.Json

/**
 * Represents the action taken by the ReAct loop:
 * either calling a tool with JSON input, or finishing with a final answer.
 */
sealed trait ReActAction

case class CallTool(name: String, input: Json) extends ReActAction
case class Finish(output: String) extends ReActAction

/**
 * Represents a single step in the ReAct execution loop,
 * consisting of a thought, an action, and an optional observation.
 */
case class ReActStep(
                     thought: String,
                     action: ReActAction,
                     observation: Option[String] = None
                     )

/**
 * Represents the bounded ReAct execution state.
 */
case class ReActState(
                      goal: String,
                      steps: List[ReActStep] = List.empty,
                      maxSteps: Int = 10,
                      done: Boolean = false,
                      reasonForStop: Option[String] = None
                      )
