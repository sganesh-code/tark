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
}
