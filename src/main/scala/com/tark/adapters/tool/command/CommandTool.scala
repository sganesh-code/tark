package com.tark.adapters.tool.command

import cats.effect.Sync
import cats.syntax.all.*
import com.tark.adapters.sandbox.docker.DockerSandbox
import com.tark.adapters.sandbox.local.LocalProcessSandbox
import com.tark.domain.context.Context
import com.tark.domain.tool.*
import com.tark.ports.outbound.tool.CommandExecutor
import io.circe.parser

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

  private def stripQuotes(s: String): String = {
    val trimmed = s.trim
    if (
      (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
      (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
      (trimmed.startsWith("`") && trimmed.endsWith("`"))
    ) trimmed.drop(1).dropRight(1).trim
    else trimmed
  }

  def commandFrom(toolCall: ToolCall): Either[String, String] =
    parser.parse(toolCall.function.arguments).leftMap(_.getMessage).flatMap { json =>
      json.hcursor.get[Option[String]]("command").leftMap(_.getMessage).flatMap {
        case Some(command) => Right(stripQuotes(command))
        case None => Left("Tool argument 'command' is missing.")
      }
    }.flatMap {
      case "" => Left("Tool argument 'command' is empty.")
      case command => Right(command)
    }

  def execute[F[_]: Sync](context: Context, toolCall: ToolCall): F[ToolResult] =
    commandFrom(toolCall) match {
      case Left(error) =>
        Sync[F].pure(ToolResult(s"Command failed: $error"))
      case Right(command) =>
        runCommand(context, command).map(ToolResult.apply)
    }

  private def runCommand[F[_]: Sync](context: Context, command: String): F[String] =
    Sync[F].blocking {
      val process = context.sandbox match {
        case Some(sandbox: DockerSandbox) =>
          Process(Seq("docker", "exec", sandbox.name, "sh", "-c", command))
        case Some(sandbox: LocalProcessSandbox) =>
          Process(Seq("sh", "-c", command), sandbox.allowedDirectory.toFile)
        case _ =>
          Process(Seq("sh", "-c", command))
      }

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
