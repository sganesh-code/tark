package com.tark.ports.shared.tool

object TextFormatter {
  def stripToolCallBlocks(s: String): String = {
    val s1 = s.replaceAll("(?s)<tool_call>.*?</tool_call>", "")
    val s2 = s1.replaceAll("(?s)<function.*?>.*?</function>", "")
    val s3 = s2.replaceAll("(?s)<parameter.*?>.*?</parameter>", "")
    val s4 = s3.replaceAll("(?s)</?tool_call>", "")
      .replaceAll("(?s)</?function.*?>", "")
      .replaceAll("(?s)</?parameter.*?>", "")
    s4.trim
  }

  def stripJsonBlocks(s: String): String = {
    val s1 = s.replaceAll("(?s)```json.*?```", "")
    val s2 = s1.replaceAll("(?s)```.*?```", "")
    s2.trim
  }

  def cleanLlmOutput(s: String): String = {
    stripToolCallBlocks(stripJsonBlocks(s))
  }

  def limitToolOutput(output: String, maxLines: Int = 15): String = {
    val lines = output.split("\n", -1)
    if (lines.length <= maxLines) {
      output
    } else {
      val truncated = lines.take(maxLines).mkString("\n")
      val remaining = lines.length - maxLines
      s"$truncated\n... [TRUNCATED - $remaining more lines]"
    }
  }
}
