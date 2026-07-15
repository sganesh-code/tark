package com.tark.domain

import com.tark.domain.ui.{Cell, Color, Screen}
import munit.FunSuite

class ScreenSpec extends FunSuite {
  test("Screen buffer: bounds checking and put operations") {
    val screen = Screen(10, 5)

    assertEquals(screen.cell(0, 0).glyph, " ")

    screen.put(1, 1, "Tark", Color.Red)
    assertEquals(screen.cell(1, 1).glyph, "T")
    assertEquals(screen.cell(1, 1).fg, Color.Red)
    assertEquals(screen.cell(2, 1).glyph, "a")
    assertEquals(screen.cell(3, 1).glyph, "r")
    assertEquals(screen.cell(4, 1).glyph, "k")

    screen.put(10, 10, Cell("X"))
    screen.put(-1, 0, Cell("Y"))
  }
}
