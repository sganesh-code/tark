package com.tark.adapters.tool.command

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.tark.domain.tool.{Tool, ToolContext, ToolType}
import com.tark.domain.Interaction
import com.tark.adapters.sandbox.docker.DockerSandbox
import com.tark.adapters.sandbox.docker.DockerSandboxManager.given
import com.tark.adapters.sandbox.local.{LocalProcessSandbox, LocalProcessSandboxManager}
import com.tark.adapters.sandbox.local.LocalProcessSandboxManager.given
import com.tark.ports.outbound.sandbox.SandboxManager

import scala.sys.process.*

/**
 * Outbound tool adapter that exposes shell command execution through the tool
 * model and delegates sandboxed execution to the SandboxManager port.
 */
object CommandTool {
  def stripQuotes(s: String): String = {
    val trimmed = s.trim
    if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
        (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
        (trimmed.startsWith("`") && trimmed.endsWith("`"))) {
      trimmed.drop(1).dropRight(1).trim
    } else {
      trimmed
    }
  }

  private def runCommand(command: String, toolContext: ToolContext): String = {
    toolContext.context.sandbox match {
      case Some(sandbox) =>
        sandbox match {
          case docker: DockerSandbox =>
            summon[SandboxManager[IO, DockerSandbox]].execute(docker, command).unsafeRunSync()
          case local: LocalProcessSandbox =>
            summon[SandboxManager[IO, LocalProcessSandbox]].execute(local, command).unsafeRunSync()
        }
      case None =>
        Process(Seq("sh", "-c", command)).!!
    }
  }

  val generic: Tool = {
    val execute: ToolContext => String = { context =>
      val rawCommand = context.args.getOrElse("command", "").trim
      val command = stripQuotes(rawCommand)
      if (command.isEmpty) {
        "Error: 'command' argument is missing."
      } else {
        try {
          runCommand(command, context)
        } catch {
          case ex: Exception => s"Command failed: ${ex.getMessage}"
        }
      }
    }
    Tool("command_executor", execute, ToolType.CommandTool)
  }

  def create(command: String): Tool = {
    val cleanCommand = stripQuotes(command)
    val execute: ToolContext => String = { context =>
        try {
          val result = runCommand(cleanCommand, context)
          val updatedContext = context.context.copy(
            memory = context.context.memory + (s"cmd_${context.executionId}" -> result)
          )
          val interaction = Interaction(
            id = s"interaction_${System.currentTimeMillis}",
            input = cleanCommand,
            output = result,
            timestamp = System.currentTimeMillis,
            toolName = s"command_${cleanCommand.replaceAll("\\s+", "_")}"
          )
          val finalContext = updatedContext.copy(
            history = updatedContext.history :+ interaction
          )
          val updatedToolContext = context.copy(context = finalContext)
          s"Command executed successfully. Result stored in memory key: cmd_${context.executionId}"
        } catch {
          case ex: Exception =>
            s"Command failed: ${ex.getMessage}"
        }
    }
    val sanitizedName = s"command_${cleanCommand.replaceAll("\\s+", "_")}"
    Tool(sanitizedName, execute, ToolType.CommandTool)
  }
}
