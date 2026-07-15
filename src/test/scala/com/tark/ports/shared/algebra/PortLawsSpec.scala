package com.tark.ports.shared.algebra

import com.tark.application.instances.all.given

import com.tark.domain.{Config, Interaction}
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.react.{Finish, ReActState, ReActStep}
import com.tark.domain.tool.{Tool, ToolContext}
import com.tark.ports.shared.algebra.{Appendable, Dispatcher, Registry, RegistryOps, Route, StateAlgebraOps, Updatable}
import com.tark.ports.shared.algebra.ConfigAlgebras.{BaseUrl, ModelId}
import com.tark.ports.shared.algebra.ConfigAlgebras.given
import com.tark.ports.shared.algebra.ContextAlgebras.given
import com.tark.ports.shared.algebra.ReActStateAlgebras.given
import com.tark.ports.outbound.context.ContextOps
import com.tark.ports.shared.config.ConfigOps
import com.tark.ports.shared.tool.ToolRegistry
import com.tark.support.PortLawChecks
import munit.FunSuite

class PortLawsSpec extends FunSuite with PortLawChecks {

  private val interaction1 = Interaction("1", "input-1", "output-1", 1L, "tool-1")
  private val interaction2 = Interaction("2", "input-2", "output-2", 2L, "tool-2")

  test("Serializable laws: Context serialization is deterministic") {
    val context = Context(Map.empty, Map("key" -> "value"), List(interaction1, interaction2))

    assertSerializableDeterministic[Context, String](context)
  }

  test("ContextOps laws: get-after-update, append order, composition, and source preservation") {
    val ops = summon[ContextOps[Context]]
    val context = Context(Map.empty, Map.empty, List(interaction1))

    val withHistory = ops.addInteraction(context, interaction2)
    assertEquals(ops.getContextHistory(withHistory), List(interaction1, interaction2))
    assertEquals(ops.getContextHistory(context), List(interaction1))

    val addLegacyA: Memory => Memory = _ + ("a" -> "1")
    val addLegacyB: Memory => Memory = _ + ("b" -> "2")
    val sequential = ops.updateMemory(ops.updateMemory(context, addLegacyA), addLegacyB)
    val composed = ops.updateMemory(context, addLegacyA.andThen(addLegacyB))

    assertEquals(ops.getMemory(sequential), ops.getMemory(composed))
    assertEquals(ops.getMemory(sequential).get("a"), Some("1"))
    assertEquals(ops.getMemory(sequential).get("b"), Some("2"))
    assertEquals(ops.getMemory(context).legacy, Map.empty[String, String])
  }

  test("ContextOps laws: updating memory and agent state preserves unrelated fields") {
    val ops = summon[ContextOps[Context]]
    val tool = Tool("lookup", (_: ToolContext) => "ok")
    val context = Context(Map("lookup" -> tool), Map.empty, List(interaction1))

    val updatedMemory = ops.updateMemory(context, _ + ("key" -> "value"))
    val updatedAgentState = ops.updateAgentState(context, _.withGoal("goal"))

    assertEquals(ops.getContextTools(updatedMemory), Map("lookup" -> tool))
    assertEquals(ops.getContextHistory(updatedMemory), List(interaction1))
    assertEquals(ops.getContextTools(updatedAgentState), Map("lookup" -> tool))
    assertEquals(ops.getContextHistory(updatedAgentState), List(interaction1))
  }

  test("ToolRegistry laws: lookup-after-register, last-write-wins, missing lookup, and source preservation") {
    val registry = summon[ToolRegistry[Context]]
    val context = Context(Map.empty, Map.empty, List.empty)

    val first = Tool("command", (_: ToolContext) => "first")
    val other = Tool("other", (_: ToolContext) => "other")
    val replacement = Tool("command", (_: ToolContext) => "replacement")

    val withFirst = registry.register(context, first)
    val withOther = registry.register(withFirst, other)
    val withReplacement = registry.register(withOther, replacement)

    assertEquals(registry.lookup(context, "command"), None)
    assertEquals(registry.lookup(withFirst, "command").map(_.execute(ToolContext(context, Map.empty, "1"))), Some("first"))
    assertEquals(registry.lookup(withOther, "other").map(_.execute(ToolContext(context, Map.empty, "2"))), Some("other"))
    assertEquals(registry.lookup(withReplacement, "command").map(_.execute(ToolContext(context, Map.empty, "3"))), Some("replacement"))
    assertEquals(registry.lookup(withReplacement, "other").map(_.execute(ToolContext(context, Map.empty, "4"))), Some("other"))
    assertEquals(registry.lookup(withReplacement, "missing"), None)
  }

  test("ConfigOps laws: getters, preservation, disjoint commutativity, and last-write-wins") {
    val ops = summon[ConfigOps[Config]]
    val config = Config(
      modelId = "model-a",
      maxTokens = 100,
      baseUrl = "http://localhost/a",
      sandboxImageName = "sandbox-a"
    )

    assertEquals(ops.getModelId(config), "model-a")
    assertEquals(ops.getMaxTokens(config), 100)
    assertEquals(ops.getBaseUrl(config), "http://localhost/a")
    assertEquals(ops.getSandboxImageName(config), "sandbox-a")

    val modelOnly = ops.withUpdatedConfig(config, Map("modelId" -> "model-b"))
    assertEquals(modelOnly.modelId, "model-b")
    assertEquals(modelOnly.maxTokens, 100)
    assertEquals(modelOnly.baseUrl, "http://localhost/a")
    assertEquals(modelOnly.sandboxImageName, "sandbox-a")
    assertEquals(config.modelId, "model-a")

    val updateModel = Map[String, Any]("modelId" -> "model-c")
    val updateBaseUrl = Map[String, Any]("baseUrl" -> "http://localhost/c")
    val modelThenUrl = ops.withUpdatedConfig(ops.withUpdatedConfig(config, updateModel), updateBaseUrl)
    val urlThenModel = ops.withUpdatedConfig(ops.withUpdatedConfig(config, updateBaseUrl), updateModel)
    assertEquals(modelThenUrl, urlThenModel)

    val overwritten = ops.withUpdatedConfig(modelOnly, Map("modelId" -> "model-final"))
    assertEquals(overwritten.modelId, "model-final")
  }

  test("Prototype Updatable laws: Context memory and agent state compose without weakening domain types") {
    val context = Context(Map.empty, Map.empty, List.empty)

    assertUpdatableComposition[Context, Memory](
      context,
      _ + ("first" -> "1"),
      _ + ("second" -> "2")
    )

    assertUpdatableComposition[Context, Option[com.tark.domain.AgentState]](
      context,
      _.orElse(Some(com.tark.domain.AgentState())).map(_.withGoal("goal")),
      _.map(_.addConstraint("constraint"))
    )

    val memory = summon[Updatable[Context, Memory]].update(context)(_ + ("key" -> "value"))
    val agentState = summon[Updatable[Context, Option[com.tark.domain.AgentState]]]
      .update(context)(_.orElse(Some(com.tark.domain.AgentState())).map(_.withGoal("goal")))

    assertEquals(memory.memory.get("key"), Some("value"))
    assertEquals(agentState.agentState.map(_.goal), Some("goal"))
    assertEquals(context.memory.legacy, Map.empty[String, String])
    assertEquals(context.agentState, None)
  }

  test("Prototype Appendable and Registry laws: Context interactions, ReAct steps, and tools") {
    val context = Context(Map.empty, Map.empty, List.empty)

    assertAppendableOrder[Context, Interaction](
      context,
      interaction1,
      interaction2,
      _.history
    )

    val state = ReActState("goal")
    val step1 = ReActStep("thought-1", Finish("answer-1"))
    val step2 = ReActStep("thought-2", Finish("answer-2"))
    assertAppendableOrder[ReActState, ReActStep](
      state,
      step1,
      step2,
      _.steps
    )

    val first = Tool("command", (_: ToolContext) => "first")
    val other = Tool("other", (_: ToolContext) => "other")
    val replacement = Tool("command", (_: ToolContext) => "replacement")
    assertRegistryMapLaws[Context, String, Tool](
      context,
      "command",
      first,
      "other",
      other,
      replacement
    )
  }

  test("RegistryOps laws: registerAll preserves order, last-write-wins, and lookupOrRaise errors on missing key") {
    val context = Context(Map.empty, Map.empty, List.empty)
    val first = Tool("command", (_: ToolContext) => "first")
    val other = Tool("other", (_: ToolContext) => "other")
    val replacement = Tool("command", (_: ToolContext) => "replacement")

    val updated = RegistryOps.registerAll[Context, String, Tool](
      context,
      List(
        "command" -> first,
        "other" -> other,
        "command" -> replacement
      )
    )

    assertEquals(summon[Registry[Context, String, Tool]].lookup(updated, "command"), Some(replacement))
    assertEquals(summon[Registry[Context, String, Tool]].lookup(updated, "other"), Some(other))

    type ErrorOr[A] = Either[Throwable, A]
    val found = RegistryOps
      .lookupOrRaise[ErrorOr, Context, String, Tool](updated, "command", new NoSuchElementException("missing command"))
    assertEquals(found, Right(replacement))

    val missing = RegistryOps.lookupOrRaise[ErrorOr, Context, String, Tool](
      updated,
      "missing",
      new NoSuchElementException("missing tool")
    )
    assertEquals(missing.left.map(_.getMessage), Left("missing tool"))
  }

  test("StateAlgebraOps laws: modify, set, and appendAll preserve update and append semantics") {
    val context = Context(Map.empty, Map.empty, List.empty)
    val memoryA = Memory(legacy = Map("a" -> "1"))
    val memoryB = Memory(legacy = Map("b" -> "2"))

    val modified = StateAlgebraOps.modify[Context, Memory](context)(_ + ("a" -> "1"))
    assertEquals(modified.memory, memoryA)
    assertEquals(context.memory.legacy, Map.empty[String, String])

    val set = StateAlgebraOps.set[Context, Memory](modified, memoryB)
    assertEquals(set.memory, memoryB)
    assertEquals(set.history, List.empty)

    val withInteractions = StateAlgebraOps.appendAll[Context, Interaction](
      context,
      List(interaction1, interaction2)
    )
    assertEquals(withInteractions.history, List(interaction1, interaction2))
    assertEquals(context.history, List.empty)

    val step1 = ReActStep("thought-1", Finish("answer-1"))
    val step2 = ReActStep("thought-2", Finish("answer-2"))
    val reactState = StateAlgebraOps.appendAll[ReActState, ReActStep](
      ReActState("goal"),
      List(step1, step2)
    )
    assertEquals(reactState.steps, List(step1, step2))
  }

  test("Prototype Config field algebras preserve type safety for same-typed fields") {
    val config = Config(
      modelId = "model-a",
      maxTokens = 100,
      baseUrl = "http://localhost/a",
      sandboxImageName = "sandbox-a"
    )

    assertUpdatableComposition[Config, ModelId](
      config,
      _ => ModelId("model-b"),
      _ => ModelId("model-c")
    )
    assertUpdatableComposition[Config, BaseUrl](
      config,
      _ => BaseUrl("http://localhost/b"),
      _ => BaseUrl("http://localhost/c")
    )

    val modelUpdated = summon[Updatable[Config, ModelId]].update(config)(_ => ModelId("model-final"))
    val urlUpdated = summon[Updatable[Config, BaseUrl]].update(config)(_ => BaseUrl("http://localhost/final"))

    assertEquals(modelUpdated.modelId, "model-final")
    assertEquals(modelUpdated.baseUrl, "http://localhost/a")
    assertEquals(urlUpdated.modelId, "model-a")
    assertEquals(urlUpdated.baseUrl, "http://localhost/final")
  }

  test("Prototype Dispatcher laws: first matching route wins and fallback handles misses") {
    case class DispatchState(events: List[String])

    val first = new Route[cats.Id, String, DispatchState] {
      override def matches(input: String): Boolean = input.startsWith("/same")
      override def run(input: String, state: DispatchState): DispatchState =
        state.copy(events = state.events :+ "first")
    }
    val second = new Route[cats.Id, String, DispatchState] {
      override def matches(input: String): Boolean = input.startsWith("/same")
      override def run(input: String, state: DispatchState): DispatchState =
        state.copy(events = state.events :+ "second")
    }
    val fallback = new Route[cats.Id, String, DispatchState] {
      override def matches(input: String): Boolean = true
      override def run(input: String, state: DispatchState): DispatchState =
        state.copy(events = state.events :+ "fallback")
    }

    val dispatcher = Dispatcher.firstMatch[cats.Id, String, DispatchState](List(first, second), fallback)

    assertEquals(dispatcher.dispatch("/same", DispatchState(List.empty)).events, List("first"))
    assertEquals(dispatcher.dispatch("/missing", DispatchState(List.empty)).events, List("fallback"))
  }
}
