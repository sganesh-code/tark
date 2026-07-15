package com.tark.ports.shared.ui

trait Glyph[A] {
  def columns(glyph: A): Int
}

object Glyph {
  // We can provide a default instance for String
  given Glyph[String] with {
    override def columns(glyph: String): Int = glyph.length // Simpler default, can be extended for wide chars later
  }

  given Glyph[Char] with {
    override def columns(glyph: Char): Int = 1
  }
}
