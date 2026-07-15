package com.tark.ports.shared.ui

import munit.FunSuite

class GlyphSpec extends FunSuite {
  test("Glyph typeclass: columns measurement") {
    val stringGlyph = summon[Glyph[String]]
    val charGlyph = summon[Glyph[Char]]

    assertEquals(stringGlyph.columns("Hello"), 5)
    assertEquals(charGlyph.columns('A'), 1)
  }

  test("Glyph typeclass laws: non-negative width, string additivity, and char width") {
    val stringGlyph = summon[Glyph[String]]
    val charGlyph = summon[Glyph[Char]]

    val left = "Tark"
    val right = " CLI"

    assert(stringGlyph.columns("") >= 0)
    assert(stringGlyph.columns(left) >= 0)
    assertEquals(stringGlyph.columns(left + right), stringGlyph.columns(left) + stringGlyph.columns(right))
    assertEquals(charGlyph.columns('A'), 1)
    assertEquals(charGlyph.columns(' '), 1)
  }
}
