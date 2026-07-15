package com.tark.adapters.ui

import cats.effect.unsafe.implicits.global
import com.tark.domain.ui.{Cell, Color, Screen, Style}
import com.tark.ports.outbound.ui.ScreenWriter
import munit.FunSuite

import java.io.{PrintWriter, StringWriter}

class ScreenWriterInstancesSpec extends FunSuite {
  import ScreenWriterInstances.given

  test("ScreenWriter: render screen to output with colors and styles") {
    val screen = Screen(2, 2)
    screen.put(0, 0, Cell("A", fg = Color.Red))
    screen.put(1, 0, Cell("B", bg = Color.Blue))
    screen.put(0, 1, Cell("C", styles = Set(Style.Bold)))
    screen.put(1, 1, Cell("D"))

    val stringWriter = new StringWriter()
    val printWriter = new PrintWriter(stringWriter)

    summon[ScreenWriter[Screen]].write(screen, printWriter).unsafeRunSync()

    val output = stringWriter.toString
    assert(output.contains("\u001b[H"))
    assert(output.contains("\u001b[2J"))
    assert(output.contains("31"))
    assert(output.contains("44"))
    assert(output.contains(";1"))
    assert(output.contains("A"))
    assert(output.contains("B"))
    assert(output.contains("C"))
    assert(output.contains("D"))
  }

  test("ScreenWriter: render screen delta correctly") {
    val oldScreen = Screen(3, 3)
    oldScreen.put(0, 0, Cell("A"))
    oldScreen.put(1, 0, Cell("B"))
    oldScreen.put(2, 0, Cell("C"))

    val newScreen = Screen(3, 3)
    newScreen.put(0, 0, Cell("A"))
    newScreen.put(1, 0, Cell("Z"))
    newScreen.put(2, 0, Cell("C"))

    val stringWriter = new StringWriter()
    val printWriter = new PrintWriter(stringWriter)

    summon[ScreenWriter[Screen]].writeDelta(newScreen, oldScreen, printWriter).unsafeRunSync()

    val output = stringWriter.toString
    assert(!output.contains("\u001b[2J"))
    assert(output.contains("\u001b[1;2H"))
    assert(output.contains("Z"))
    assert(!output.contains("A"))
    assert(!output.contains("C"))
  }
}
