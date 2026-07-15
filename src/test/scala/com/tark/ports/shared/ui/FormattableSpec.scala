package com.tark.ports.shared.ui

import com.tark.application.instances.all.given

import com.tark.domain.ui.{Cell, Color, Screen, Style}
import com.tark.support.PortLawChecks
import munit.FunSuite

class FormattableSpec extends FunSuite with PortLawChecks {

  private def screenSnapshot(screen: Screen): Vector[Vector[Cell]] =
    (0 until screen.height).map { y =>
      (0 until screen.width).map(x => screen.cell(x, y)).toVector
    }.toVector

  test("Formattable[Cell] - basic and chained formatting") {
    val cell = Cell("A")

    // Test withFg
    val withFg = cell.withFg(Color.Red)
    assertEquals(withFg.fg, Color.Red)
    assertEquals(withFg.bg, Color.Default)
    assertEquals(withFg.styles, Set.empty[Style])

    // Test withBg
    val withBg = cell.withBg(Color.Blue)
    assertEquals(withBg.fg, Color.Default)
    assertEquals(withBg.bg, Color.Blue)
    assertEquals(withBg.styles, Set.empty[Style])

    // Test withStyle and withStyles
    val withStyle = cell.withStyle(Style.Bold)
    assertEquals(withStyle.styles, Set(Style.Bold))

    val withStyles = cell.withStyles(Set(Style.Bold, Style.Underline))
    assertEquals(withStyles.styles, Set(Style.Bold, Style.Underline))

    // Test chaining
    val chained = cell.withFg(Color.Green).withBg(Color.Yellow).withStyle(Style.Italic)
    assertEquals(chained.glyph, "A")
    assertEquals(chained.fg, Color.Green)
    assertEquals(chained.bg, Color.Yellow)
    assertEquals(chained.styles, Set(Style.Italic))

    // Test formatted method with Options
    val optFormatted = cell.formatted(fg = Some(Color.Cyan), styles = Some(Set(Style.Underline)))
    assertEquals(optFormatted.fg, Color.Cyan)
    assertEquals(optFormatted.bg, Color.Default)
    assertEquals(optFormatted.styles, Set(Style.Underline))
  }

  test("Formattable[Screen] - formatting an entire screen") {
    val screen = Screen(3, 3)
    screen.put(0, 0, "A")
    screen.put(1, 1, "B")

    // Format screen
    val formattedScreen = screen.withFg(Color.BrightMagenta).withBg(Color.BrightBlack).withStyle(Style.Reverse)

    for {
      y <- 0 until 3
      x <- 0 until 3
    } {
      val cell = formattedScreen.cell(x, y)
      assertEquals(cell.fg, Color.BrightMagenta)
      assertEquals(cell.bg, Color.BrightBlack)
      assertEquals(cell.styles, Set(Style.Reverse))
    }
  }

  test("Formattable[Message] - formatting message types") {
    val userMsg: Message = Message.User("hello")
    val formattedUser = userMsg.withFg(Color.BrightYellow).withBg(Color.Black).withStyle(Style.Bold)

    assertEquals(formattedUser.text, "hello")
    assertEquals(formattedUser.fg, Color.BrightYellow)
    assertEquals(formattedUser.bg, Color.Black)
    assertEquals(formattedUser.styles, Set(Style.Bold))

    // AI Msg
    val aiMsg: Message = Message.AI("hi")
    val formattedAi = aiMsg.withFg(Color.BrightBlue).withBg(Color.Default).withStyles(Set(Style.Dim, Style.Italic))
    assertEquals(formattedAi.text, "hi")
    assertEquals(formattedAi.fg, Color.BrightBlue)
    assertEquals(formattedAi.bg, Color.Default)
    assertEquals(formattedAi.styles, Set(Style.Dim, Style.Italic))

    // System Msg
    val sysMsg: Message = Message.System("system")
    val formattedSys = sysMsg.withFg(Color.Red).withStyle(Style.Underline)
    assertEquals(formattedSys.text, "system")
    assertEquals(formattedSys.fg, Color.Red)
    assertEquals(formattedSys.styles, Set(Style.Underline))
  }

  test("Layout wrapping of custom-formatted messages") {
    val customMsg = Message.User("This is a very long message that should wrap", fg = Color.BrightYellow, bg = Color.Black, styles = Set(Style.Bold))
    val wrapped = Layout.wrapMessages(Vector(customMsg), 15)

    assert(wrapped.length > 1)
    wrapped.foreach { msg =>
      assertEquals(msg.fg, Color.BrightYellow)
      assertEquals(msg.bg, Color.Black)
      assertEquals(msg.styles, Set(Style.Bold))
    }
  }

  test("CellRenderer[Cell, String] - renders ANSI string formats correctly") {
    val renderer = summon[CellRenderer[Cell, String]]
    val cell = Cell("T", fg = Color.Red, bg = Color.Blue, styles = Set(Style.Bold))
    val rendered = renderer.render(cell)

    assertRendererDeterministic[Cell, String](cell)
    assertRendererIncludes(cell, List("31", "44", "1", "T"))

    // Ensure red foreground (31), blue background (44), and bold (1) are present in the ANSI string
    assert(rendered.contains("31"))
    assert(rendered.contains("44"))
    assert(rendered.contains("1"))
    assert(rendered.contains("T"))
    assert(rendered.startsWith("\u001b["))
    assert(rendered.endsWith("\u001b[0m"))
  }

  test("Formattable[Cell] laws: identity, field preservation, last-write-wins, and idempotence") {
    val cell = Cell("A", fg = Color.Green, bg = Color.Black, styles = Set(Style.Bold))

    assertFormattableIdentity(cell)
    assertFormattableLastWriteWins(cell, Color.Red, Color.Blue)(identity)

    assertEquals(cell.formatted(), cell)

    val onlyFg = cell.withFg(Color.Red)
    assertEquals(onlyFg.glyph, "A")
    assertEquals(onlyFg.fg, Color.Red)
    assertEquals(onlyFg.bg, Color.Black)
    assertEquals(onlyFg.styles, Set(Style.Bold))

    val lastWriteWins = cell.withFg(Color.Red).withFg(Color.Blue)
    assertEquals(lastWriteWins, cell.withFg(Color.Blue))

    val once = cell.formatted(
      fg = Some(Color.Cyan),
      bg = Some(Color.BrightBlack),
      styles = Some(Set(Style.Italic))
    )
    val twice = once.formatted(
      fg = Some(Color.Cyan),
      bg = Some(Color.BrightBlack),
      styles = Some(Set(Style.Italic))
    )
    assertFormattableIdempotence(
      cell,
      fg = Some(Color.Cyan),
      bg = Some(Color.BrightBlack),
      styles = Some(Set(Style.Italic))
    )(identity)
    assertEquals(twice, once)
  }

  test("Formattable[Message] laws: formatting preserves message shape and text") {
    val messages: List[Message] = List(
      Message.User("user"),
      Message.AI("ai"),
      Message.System("system")
    )

    messages.foreach { message =>
      assertFormattableIdentity(message)
      assertFormattableIdempotence(
        message,
        fg = Some(Color.BrightYellow),
        bg = Some(Color.Black),
        styles = Some(Set(Style.Underline))
      )(m => (m.getClass, m.text, m.fg, m.bg, m.styles))

      val formatted = message.withFg(Color.BrightYellow).withBg(Color.Black).withStyle(Style.Underline)

      assertEquals(formatted.getClass, message.getClass)
      assertEquals(formatted.text, message.text)
      assertEquals(formatted.fg, Color.BrightYellow)
      assertEquals(formatted.bg, Color.Black)
      assertEquals(formatted.styles, Set(Style.Underline))
    }
  }

  test("Formattable[Screen] laws: formatting preserves dimensions and glyphs and is idempotent") {
    val screen = Screen(2, 2)
    screen.put(0, 0, Cell("A"))
    screen.put(1, 0, Cell("B", fg = Color.Red))
    screen.put(0, 1, Cell("C", bg = Color.Blue))
    screen.put(1, 1, Cell("D", styles = Set(Style.Bold)))

    val originalGlyphs = screenSnapshot(screen).map(_.map(_.glyph))
    val formatted = screen.formatted(
      fg = Some(Color.Green),
      bg = Some(Color.Black),
      styles = Some(Set(Style.Italic))
    )

    assertFormattableIdempotence(
      screen,
      fg = Some(Color.Green),
      bg = Some(Color.Black),
      styles = Some(Set(Style.Italic))
    )(screenSnapshot)

    assertEquals(formatted.width, 2)
    assertEquals(formatted.height, 2)
    assertEquals(screenSnapshot(formatted).map(_.map(_.glyph)), originalGlyphs)

    val onceSnapshot = screenSnapshot(formatted)
    val formattedAgain = formatted.formatted(
      fg = Some(Color.Green),
      bg = Some(Color.Black),
      styles = Some(Set(Style.Italic))
    )
    assertEquals(screenSnapshot(formattedAgain), onceSnapshot)
  }
}
