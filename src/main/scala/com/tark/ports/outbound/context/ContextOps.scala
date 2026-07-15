package com.tark.ports.outbound.context

import com.tark.domain.memory.Memory
import com.tark.domain.tool.Tool
import com.tark.domain.{AgentState, Interaction}

trait ContextOps[C] {
  def getContextTools(context: C): Map[String, Tool]
  def updateContext(context: C, toolName: String, memoryValue: String): C
  def getContextHistory(context: C): List[Interaction]
  def addInteraction(context: C, interaction: Interaction): C
  def getAgentState(context: C): Option[AgentState]
  def updateAgentState(context: C, f: AgentState => AgentState): C
  def getMemory(context: C): Memory
  def updateMemory(context: C, f: Memory => Memory): C
}
