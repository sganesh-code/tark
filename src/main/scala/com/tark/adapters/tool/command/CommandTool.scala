package com.tark.adapters.tool.command

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.domain.context.Context
import com.tark.domain.tool.*
import com.tark.ports.outbound.tool.CommandExecutor

import scala.sys.process.*

object CommandTool {
  given commandExecutor[F[_]: Sync]: CommandExecutor[F] with {
    override def definition: ToolDefinition = CommandTool.definition
    override def execute(context: Context, toolCall: ToolCall): F[ToolResult] =
      CommandTool.execute(context, toolCall)
  }

  val definition: ToolDefinition = ToolDefinition(
    `type` = "function",
    function = OpenAIFunction(
      name = "command_executor",
      description = "Execute linux shell commands inside the configured sandbox",
      parameters = OpenAIFunctionParams.Str(
        description = "JSON object containing a command field with the full command to execute"
      )
    )
  )

  def commandFrom(toolCall: ToolCall): Either[String, String] =
    CommandArgumentExtractor.commandFrom(toolCall)

  def execute[F[_]: Sync](context: Context, toolCall: ToolCall): F[ToolResult] =
    CommandArgumentExtractor.commandFrom(toolCall) match {
      case Left(error) =>
        Sync[F].pure(ToolResult(s"Command failed: $error"))
      case Right(command) =>
        runCommand(context, command).map(ToolResult.apply)
    }

  private def runCommand[F[_]: Sync](context: Context, command: String): F[String] =
    Sync[F].blocking {
      val (cmdSeq, workingDir) = context.sandbox match {
        case Some(sandbox) => sandbox.buildProcess(command)
        case None => (Seq("sh", "-c", command), None)
      }
      val process = Process(cmdSeq, workingDir)

      val stdout = new java.lang.StringBuilder
      val stderr = new java.lang.StringBuilder
      val logger = ProcessLogger(stdout.append(_).append("\n"), stderr.append(_).append("\n"))
      val exitCode = process.!(logger)

      if (exitCode == 0) {
        stdout.toString.trim
      } else {
        val err = stderr.toString.trim
        val out = stdout.toString.trim
        val msg = if (err.nonEmpty) err else if (out.nonEmpty) out else "Unknown error"
        s"Command failed with exit code $exitCode: $msg"
      }
    }
}
