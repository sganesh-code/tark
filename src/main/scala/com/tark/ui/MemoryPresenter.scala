package com.tark.ui

import com.tark.domain.context.Context

object MemoryPresenter {
  def renderMemory(context: Context): String = {
    val mem = context.memory
    val sb = new java.lang.StringBuilder()
    sb.append("[Memory Layers Status]\n")

    sb.append("  * Working Memory: ")
    mem.working match {
      case Some(w) =>
        sb.append(s"Active (Goal = '${w.goal}', Done = ${w.done})\n")
        if w.constraints.nonEmpty then sb.append(s"    - Constraints: ${w.constraints.mkString(", ")}\n")
        if w.plan.nonEmpty then sb.append(s"    - Plan Steps: ${w.plan.size} steps\n")
      case None =>
        sb.append("Inactive\n")
    }

    sb.append(s"  * Episodic Memory: ${mem.episodic.episodes.size} episodes\n")
    mem.episodic.episodes.zipWithIndex.foreach { case (ep, idx) =>
      sb.append(s"    [$idx] Session '${ep.sessionId}': ${ep.summary}\n")
      if ep.keyTakeaways.nonEmpty then sb.append(s"         Takeaways: ${ep.keyTakeaways.mkString(", ")}\n")
    }

    sb.append(s"  * Procedural Memory: ${context.tools.size} tools, ${mem.procedural.skills.size} skills\n")
    if context.tools.nonEmpty then
      sb.append(s"    - Registered Tools: ${context.tools.map(_.function.name).mkString(", ")}\n")
    mem.procedural.skills.foreach { skill =>
      sb.append(s"    - Skill '${skill.name}': ${skill.description}\n")
    }

    sb.toString.trim
  }
}
