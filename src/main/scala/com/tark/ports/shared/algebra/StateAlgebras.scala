package com.tark.ports.shared.algebra

import cats.MonadThrow
import com.tark.domain.{AgentState, Config, Interaction}
import com.tark.domain.context.Context
import com.tark.domain.memory.Memory
import com.tark.domain.react.{ReActState, ReActStep}
import com.tark.domain.tool.Tool

trait Contains[A, B] {
  def get(source: A): B
}

trait Updatable[A, B] extends Contains[A, B] {
  def update(source: A)(f: B => B): A

  def set(source: A, value: B): A =
    update(source)(_ => value)
}

trait Appendable[A, B] {
  def append(source: A, value: B): A
}

trait Registry[C, K, V] {
  def register(container: C, key: K, value: V): C
  def lookup(container: C, key: K): Option[V]
}

object Contains {
  def apply[A, B](using contains: Contains[A, B]): Contains[A, B] = contains
}

object Updatable {
  def apply[A, B](using updatable: Updatable[A, B]): Updatable[A, B] = updatable
}

object Appendable {
  def apply[A, B](using appendable: Appendable[A, B]): Appendable[A, B] = appendable
}

object Registry {
  def apply[C, K, V](using registry: Registry[C, K, V]): Registry[C, K, V] = registry
}

object RegistryOps {
  def registerAll[C, K, V](container: C, entries: Iterable[(K, V)])(using registry: Registry[C, K, V]): C =
    entries.foldLeft(container) { case (current, (key, value)) =>
      registry.register(current, key, value)
    }

  def lookupOrRaise[F[_]: MonadThrow, C, K, V](
    container: C,
    key: K,
    error: => Throwable
  )(using registry: Registry[C, K, V]): F[V] =
    registry.lookup(container, key) match {
      case Some(value) => MonadThrow[F].pure(value)
      case None => MonadThrow[F].raiseError(error)
    }
}

object StateAlgebraOps {
  def modify[A, B](source: A)(f: B => B)(using updatable: Updatable[A, B]): A =
    updatable.update(source)(f)

  def set[A, B](source: A, value: B)(using updatable: Updatable[A, B]): A =
    updatable.set(source, value)

  def appendAll[A, B](source: A, values: Iterable[B])(using appendable: Appendable[A, B]): A =
    values.foldLeft(source)(appendable.append)
}

object ContextAlgebras {
  given Updatable[Context, Memory] with {
    override def get(source: Context): Memory =
      source.memory

    override def update(source: Context)(f: Memory => Memory): Context =
      source.copy(memory = f(source.memory))
  }

  given Updatable[Context, Option[AgentState]] with {
    override def get(source: Context): Option[AgentState] =
      source.agentState

    override def update(source: Context)(f: Option[AgentState] => Option[AgentState]): Context =
      source.copy(memory = source.memory.copy(working = f(source.agentState)))
  }

  given Appendable[Context, Interaction] with {
    override def append(source: Context, value: Interaction): Context =
      source.copy(history = source.history :+ value)
  }

  given Registry[Context, String, Tool] with {
    override def register(container: Context, key: String, value: Tool): Context =
      container.copy(tools = container.tools + (key -> value))

    override def lookup(container: Context, key: String): Option[Tool] =
      container.tools.get(key)
  }
}

object ConfigAlgebras {
  final case class ModelId(value: String)
  final case class MaxTokens(value: Int)
  final case class BaseUrl(value: String)
  final case class SandboxImageName(value: String)

  given Updatable[Config, ModelId] with {
    override def get(source: Config): ModelId =
      ModelId(source.modelId)

    override def update(source: Config)(f: ModelId => ModelId): Config =
      source.copy(modelId = f(ModelId(source.modelId)).value)
  }

  given Updatable[Config, MaxTokens] with {
    override def get(source: Config): MaxTokens =
      MaxTokens(source.maxTokens)

    override def update(source: Config)(f: MaxTokens => MaxTokens): Config =
      source.copy(maxTokens = f(MaxTokens(source.maxTokens)).value)
  }

  given Updatable[Config, BaseUrl] with {
    override def get(source: Config): BaseUrl =
      BaseUrl(source.baseUrl)

    override def update(source: Config)(f: BaseUrl => BaseUrl): Config =
      source.copy(baseUrl = f(BaseUrl(source.baseUrl)).value)
  }

  given Updatable[Config, SandboxImageName] with {
    override def get(source: Config): SandboxImageName =
      SandboxImageName(source.sandboxImageName)

    override def update(source: Config)(f: SandboxImageName => SandboxImageName): Config =
      source.copy(sandboxImageName = f(SandboxImageName(source.sandboxImageName)).value)
  }
}

object ReActStateAlgebras {
  given Appendable[ReActState, ReActStep] with {
    override def append(source: ReActState, value: ReActStep): ReActState =
      source.copy(steps = source.steps :+ value)
  }
}
