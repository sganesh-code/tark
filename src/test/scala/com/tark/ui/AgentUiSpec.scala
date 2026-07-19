package com.tark.ui

import munit.FunSuite

class AgentUiSpec extends FunSuite {
  test("SpinnerFrames animatable advances cyclically") {
    val frames = SpinnerFrames(Vector("a", "b"))
    val animatable = summon[Animatable[SpinnerFrames]]

    assertEquals(animatable.frame(frames, 0), "a")
    assertEquals(animatable.nextIdx(frames, 0), 1)
    assertEquals(animatable.nextIdx(frames, 1), 0)
  }

  test("InputResult models terminal line, cancellation, and exit") {
    assertEquals(InputResult.Line("hello"), InputResult.Line("hello"))
    assertEquals(InputResult.Cancelled, InputResult.Cancelled)
    assertEquals(InputResult.Exit, InputResult.Exit)
  }

  test("TerminalStyle provides portable style values without adapter dependencies") {
    assertEquals(TerminalStyle.Agent.foreground, TerminalColor.Cyan)
    assert(TerminalStyle.Agent.italic)
    assertEquals(TerminalStyle.Error.foreground, TerminalColor.Red)
  }

  test("PanelRenderer word wrapping and padding wraps correctly") {
    val config = PanelConfig(width = 20, borderStyle = BorderStyle.None, maxLines = 5)
    val state = PanelState(config, Vector("This is a very long text that must be wrapped into multiple lines correctly."))
    val rendered = summon[PanelRenderer[PanelState]].render(state)

    // With width = 20 and BorderStyle.None, innerWidth is 20.
    // Check that each rendered line length is exactly 20.
    rendered.foreach { line =>
      assertEquals(line.length, 20)
    }

    // It should have truncated to maxLines = 5
    assertEquals(rendered.size, 5)
  }

  test("PanelRenderer handles different BorderStyles correctly") {
    val config = PanelConfig(width = 10, borderStyle = BorderStyle.Ascii, maxLines = 3)
    val state = PanelState(config, Vector("Hello", "World"))
    val rendered = summon[PanelRenderer[PanelState]].render(state)

    // Width = 10, BorderStyle.Ascii has borders.
    // Top border: +--------+ (size 10)
    // Left/Right: |Hello   | (size 10)
    // Bottom border: +--------+ (size 10)
    assertEquals(rendered.size, 4) // Top + Hello + World + Bottom
    assertEquals(rendered(0), "+--------+")
    assertEquals(rendered(1), "|Hello   |")
    assertEquals(rendered(2), "|World   |")
    assertEquals(rendered(3), "+--------+")
  }
}
