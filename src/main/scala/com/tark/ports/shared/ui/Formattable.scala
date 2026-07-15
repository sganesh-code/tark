package com.tark.ports.shared.ui

import com.tark.domain.ui.{Cell, Color, Screen, Style}

trait Formattable[A] {
  def format(value: A, fg: Option[Color], bg: Option[Color], styles: Option[Set[Style]]): A
}

extension [A](value: A)(using f: Formattable[A]) {
  def formatted(
    fg: Option[Color] = None,
    bg: Option[Color] = None,
    styles: Option[Set[Style]] = None
  ): A = f.format(value, fg, bg, styles)

  def withFg(fg: Color): A = f.format(value, fg = Some(fg), bg = None, styles = None)
  def withBg(bg: Color): A = f.format(value, fg = None, bg = Some(bg), styles = None)
  def withStyles(styles: Set[Style]): A = f.format(value, fg = None, bg = None, styles = Some(styles))
  def withStyle(style: Style): A = f.format(value, fg = None, bg = None, styles = Some(Set(style)))
}

object Formattable {
  def apply[A](using ev: Formattable[A]): Formattable[A] = ev

  given Formattable[Cell] with {
    override def format(cell: Cell, fg: Option[Color], bg: Option[Color], styles: Option[Set[Style]]): Cell = {
      cell.copy(
        fg = fg.getOrElse(cell.fg),
        bg = bg.getOrElse(cell.bg),
        styles = styles.getOrElse(cell.styles)
      )
    }
  }

  given Formattable[Screen] with {
    override def format(screen: Screen, fg: Option[Color], bg: Option[Color], styles: Option[Set[Style]]): Screen = {
      for {
        y <- 0 until screen.height
        x <- 0 until screen.width
      } {
        val original = screen.cell(x, y)
        val updated = summon[Formattable[Cell]].format(original, fg, bg, styles)
        screen.put(x, y, updated)
      }
      screen
    }
  }
}
