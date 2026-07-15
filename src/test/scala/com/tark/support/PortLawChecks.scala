package com.tark.support

import com.tark.application.instances.all.given
import com.tark.adapters.ui.ScreenWriterInstances.given

import com.tark.domain.ui.{Color, Style}
import com.tark.ports.shared.serialization.Serializable
import com.tark.ports.shared.algebra.{Appendable, Registry, Updatable}
import com.tark.ports.shared.ui.{CellRenderer, Formattable}

trait PortLawChecks { self: munit.FunSuite =>

  def assertSerializableDeterministic[A, R](value: A)(using serializer: Serializable[A, R]): Unit = {
    val first = serializer.serialize(value)
    val second = serializer.serialize(value)

    assertEquals(second, first)
  }

  def assertUpdatableComposition[A, B](
    source: A,
    first: B => B,
    second: B => B
  )(using updatable: Updatable[A, B]): Unit = {
    val sequential = updatable.update(updatable.update(source)(first))(second)
    val composed = updatable.update(source)(first.andThen(second))

    assertEquals(updatable.get(sequential), updatable.get(composed))
    assertEquals(updatable.get(updatable.set(source, updatable.get(source))), updatable.get(source))
  }

  def assertAppendableOrder[A, B](
    source: A,
    first: B,
    second: B,
    values: A => List[B]
  )(using appendable: Appendable[A, B]): Unit = {
    val appended = appendable.append(appendable.append(source, first), second)

    assertEquals(values(appended).takeRight(2), List(first, second))
    assertEquals(values(source).contains(first), false)
    assertEquals(values(source).contains(second), false)
  }

  def assertRegistryMapLaws[C, K, V](
    source: C,
    firstKey: K,
    firstValue: V,
    otherKey: K,
    otherValue: V,
    replacementValue: V
  )(using registry: Registry[C, K, V]): Unit = {
    val withFirst = registry.register(source, firstKey, firstValue)
    val withOther = registry.register(withFirst, otherKey, otherValue)
    val withReplacement = registry.register(withOther, firstKey, replacementValue)

    assertEquals(registry.lookup(source, firstKey), None)
    assertEquals(registry.lookup(withFirst, firstKey), Some(firstValue))
    assertEquals(registry.lookup(withOther, otherKey), Some(otherValue))
    assertEquals(registry.lookup(withReplacement, firstKey), Some(replacementValue))
    assertEquals(registry.lookup(withReplacement, otherKey), Some(otherValue))
  }

  def assertFormattableIdentity[A](value: A)(using formattable: Formattable[A]): Unit = {
    assertEquals(formattable.format(value, None, None, None), value)
  }

  def assertFormattableIdempotence[A](
    value: A,
    fg: Option[Color],
    bg: Option[Color],
    styles: Option[Set[Style]]
  )(snapshot: A => Any)(using formattable: Formattable[A]): Unit = {
    val once = formattable.format(value, fg, bg, styles)
    val twice = formattable.format(once, fg, bg, styles)

    assertEquals(snapshot(twice), snapshot(once))
  }

  def assertFormattableLastWriteWins[A](
    value: A,
    firstFg: Color,
    lastFg: Color
  )(snapshot: A => Any)(using formattable: Formattable[A]): Unit = {
    val sequential = formattable.format(
      formattable.format(value, Some(firstFg), None, None),
      Some(lastFg),
      None,
      None
    )
    val direct = formattable.format(value, Some(lastFg), None, None)

    assertEquals(snapshot(sequential), snapshot(direct))
  }

  def assertRendererDeterministic[A, R](value: A)(using renderer: CellRenderer[A, R]): Unit = {
    assertEquals(renderer.render(value), renderer.render(value))
  }

  def assertRendererIncludes[A](value: A, fragments: List[String])(using renderer: CellRenderer[A, String]): Unit = {
    val rendered = renderer.render(value)

    fragments.foreach(fragment => assert(rendered.contains(fragment), clue(rendered)))
  }
}
