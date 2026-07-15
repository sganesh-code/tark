package com.tark.domain.ui

import com.tark.domain.ui.{Cell, Color, Screen, Style}

final class Screen(
                    val width: Int,
                    val height: Int
                  ) {
  private val cells = Array.fill(width * height)(Cell())
  inline def index(x: Int, y: Int): Int = y * width + x

  def cell(x: Int, y: Int): Cell = cells(index(x, y))

  def put(x: Int, y: Int, cell: Cell): Unit =
    if (x >= 0 && x < width && y >= 0 && y < height)
      cells(index(x, y)) = cell

  def put(x: Int, y: Int, text: String, fg: Color = Color.Default): Unit = {
    text.zipWithIndex.foreach {
      case (c, i) =>
        if (x + i < width)
          put(x + i, y, Cell(c.toString, fg))
    }
  }

  def put(x: Int, y: Int, text: String, fg: Color, bg: Color, styles: Set[Style]): Unit = {
    text.zipWithIndex.foreach {
      case (c, i) =>
        if (x + i < width)
          put(x + i, y, Cell(c.toString, fg, bg, styles))
    }
  }
}
