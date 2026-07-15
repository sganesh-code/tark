package com.tark.domain.ui

case class Cell(
               glyph: String = " ",
               fg: Color = Color.Default,
               bg: Color = Color.Default,
               styles: Set[Style] = Set.empty
               )
