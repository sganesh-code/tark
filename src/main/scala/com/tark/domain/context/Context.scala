package com.tark.domain.context

import com.tark.domain.memory.Memory
import com.tark.domain.sandbox.Sandbox
import com.tark.domain.tool.Tool
import com.tark.domain.{AgentState, Interaction}

case class Context(
                  tools: Map[String, Tool],
                  memory: Memory = Memory(),
                  history: List[Interaction],
                  sandbox: Option[Sandbox] = None
                  ) {

  // Copy-based helper methods on Context to easily and chainably update the nested AgentState

  def updateAgentState(f: AgentState => AgentState): Context = {
    val currentState = memory.working.getOrElse(AgentState())
    val updatedMemory = memory.copy(working = Some(f(currentState)))
    this.copy(memory = updatedMemory)
  }

  def agentState: Option[AgentState] = memory.working

  def withGoal(g: String): Context = updateAgentState(_.withGoal(g))
  def withDeliverable(d: String): Context = updateAgentState(_.withDeliverable(d))
  def addConstraint(c: String): Context = updateAgentState(_.addConstraint(c))
  def addAssumption(a: String): Context = updateAgentState(_.addAssumption(a))
  def addKnownFact(f: String): Context = updateAgentState(_.addKnownFact(f))
  def addOpenQuestion(q: String): Context = updateAgentState(_.addOpenQuestion(q))
  def withPlan(p: List[String]): Context = updateAgentState(_.withPlan(p))
  def withCurrentStep(step: Int): Context = updateAgentState(_.withCurrentStep(step))
  def completeStep(step: String): Context = updateAgentState(_.completeStep(step))
  def addToolResult(r: String): Context = updateAgentState(_.addToolResult(r))
  def withCandidateAnswer(ans: Option[String]): Context = updateAgentState(_.withCandidateAnswer(ans))
  def withConfidence(conf: Double): Context = updateAgentState(_.withConfidence(conf))
  def withDone(done: Boolean, reason: Option[String] = None): Context = updateAgentState(_.withDone(done, reason))
}

object Context {
  // Overloaded constructors to support legacy Map[String, String] syntax in tests and instantiation
  def apply(tools: Map[String, Tool], memory: Map[String, String], history: List[Interaction]): Context =
    new Context(tools, Memory(legacy = memory), history, None)

  def apply(tools: Map[String, Tool], memory: Map[String, String], history: List[Interaction], sandbox: Option[Sandbox]): Context =
    new Context(tools, Memory(legacy = memory), history, sandbox)

  /**
   * Deserializes the unified Memory object from a saved markdown file by extracting
   * the embedded MEMORY_JSON comment block.
   */
  def deserialize(markdown: String): Either[String, Memory] = {
    val prefix = "<!-- MEMORY_JSON\n"
    val suffix = "\n-->"
    val startIdx = markdown.indexOf(prefix)
    if (startIdx != -1) {
      val endIdx = markdown.indexOf(suffix, startIdx + prefix.length)
      if (endIdx != -1) {
        val jsonStr = markdown.substring(startIdx + prefix.length, endIdx).trim
        import cats.syntax.all.*
        import io.circe.parser.*
        decode[Memory](jsonStr).leftMap(_.getMessage)
      } else {
        Left("MEMORY_JSON comment not closed properly.")
      }
    } else {
      Left("MEMORY_JSON comment not found in markdown.")
    }
  }
}
