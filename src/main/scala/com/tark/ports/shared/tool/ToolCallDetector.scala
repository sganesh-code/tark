package com.tark.ports.shared.tool

import com.tark.domain.tool.ToolCallRequest
import com.tark.ports.shared.tool.ToolCallDetector

trait ToolCallDetector {
  def detect(reply: String): Option[ToolCallRequest]
}

object ToolCallDetector {
  given ToolCallDetector with {
    override def detect(reply: String): Option[ToolCallRequest] = {
      val msg = reply.trim
      
      // 1. Check for EXECUTE_COMMAND: anywhere in the message
      val execCommandPattern = """(?s).*?EXECUTE_COMMAND:\s*([^\n]+).*?""".r
      
      // 2. Check for CALL_TOOL: anywhere in the message
      val callToolPattern = """(?s).*?CALL_TOOL:\s*([^\s\n]+).*?""".r
      val argsPattern = """(?s).*?args:\s*([^\n]+).*?""".r
      
      // 3. Check for legacy patterns anywhere in the message
      val executePattern = """(?s).*?execute:\s*([^\n]+).*?""".r
      val runPattern = """(?s).*?run:\s*([^\n]+).*?""".r

      if (msg.contains("EXECUTE_COMMAND:")) {
        execCommandPattern.findFirstMatchIn(msg).map { m =>
          ToolCallRequest("command_executor", Map("command" -> m.group(1).trim))
        }
      } else if (msg.contains("CALL_TOOL:")) {
        val optName = callToolPattern.findFirstMatchIn(msg).map(_.group(1).trim)
        val optArgsStr = argsPattern.findFirstMatchIn(msg).map(_.group(1).trim)
        
        optName.map { name =>
          val argsMap = optArgsStr.map { argsStr =>
            argsStr.split(",").flatMap { pair =>
              val kv = pair.split("=")
              if (kv.length == 2) Some(kv(0).trim -> kv(1).trim) else None
            }.toMap
          }.getOrElse(Map.empty[String, String])
          ToolCallRequest(name, argsMap)
        }
      } else {
        // Try searching for execute: or run: legacy patterns
        executePattern.findFirstMatchIn(msg).map { m =>
          ToolCallRequest("command_executor", Map("command" -> m.group(1).trim))
        }.orElse {
          runPattern.findFirstMatchIn(msg).map { m =>
            ToolCallRequest("command_executor", Map("command" -> m.group(1).trim))
          }
        }
      }
    }
  }
}
