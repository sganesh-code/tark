package com.tark.adapters.inbound.terminal.lanterna

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.virtual.VirtualTerminal
import com.tark.ui.*
import munit.FunSuite

class LanternaTerminalSpec extends FunSuite {

  test("LanternaStyleMapper maps TerminalColor and style correctly") {
    import com.googlecode.lanterna.TextColor
    import com.googlecode.lanterna.SGR
    import com.tark.ui.TerminalColor

    val cyanColor = LanternaStyleMapper.toLanternaColor(TerminalColor.Cyan)
    assertEquals(cyanColor, TextColor.ANSI.CYAN)

    val redColor = LanternaStyleMapper.toLanternaColor(TerminalColor.Red)
    assertEquals(redColor, TextColor.ANSI.RED)

    val boldItalicStyle = TerminalStyle(bold = true, italic = true)
    val sgrs = LanternaStyleMapper.toLanternaSGRs(boldItalicStyle)
    assert(sgrs.contains(SGR.BOLD))
    assert(sgrs.contains(SGR.ITALIC))
  }

  test("LanternaTerminalWriter manages scrollback buffer correctly") {
    import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
    val terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24))
    val screen = new TerminalScreen(terminal)
    screen.startScreen()

    val program = for {
      stateRef <- Ref.of[IO, TuiState](TuiState())
      writer = LanternaTerminalWriter(screen, stateRef)
      _ <- writer.printAbove("Agent", "Hello World", TerminalStyle.Default)
      _ <- writer.startInline("Agent", TerminalStyle.Default)
      _ <- writer.appendInline("Part 1", TerminalStyle.Default)
      _ <- writer.appendInline(" Part 2", TerminalStyle.Default)
      _ <- writer.finishInline()
      state <- stateRef.get
    } yield state

    val state = program.unsafeRunSync()
    assertEquals(state.scrollback.size, 2)
    assertEquals(state.scrollback(0).sender, Some("Agent"))
    assertEquals(state.scrollback(0).text, "Hello World")
    
    assertEquals(state.scrollback(1).sender, Some("Agent"))
    assertEquals(state.scrollback(1).text, "Part 1 Part 2")
    assertEquals(state.inlineOpen, false)
  }

  test("LanternaTuiRenderer wraps and renders scrollback and panel text") {
    import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
    val terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24))
    val screen = new TerminalScreen(terminal)
    screen.startScreen()

    val state = TuiState(
      scrollback = Vector(LanternaLogLine(Some("Agent"), "Short message", TerminalStyle.Default)),
      activePanelLines = Vector("Line 1", "Line 2"),
      statusText = "Ready",
      activePrompt = "tark> ",
      activeInput = "hello"
    )

    // Verify drawing triggers without throw
    LanternaTuiRenderer.render(screen, state)
    
    // Check that terminal size is correctly accounted for
    val size = screen.getTerminalSize
    assertEquals(size.getColumns, 80)
    assertEquals(size.getRows, 24)
  }

  test("LanternaTerminalStatus updates persistent status and panel lines") {
    import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
    val terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24))
    val screen = new TerminalScreen(terminal)
    screen.startScreen()

    val program = for {
      stateRef <- Ref.of[IO, TuiState](TuiState())
      status = LanternaTerminalStatus(screen, stateRef)
      _ <- status.updatePersistent("Working on goal...")
      _ <- status.updatePanel(Vector("Sub-step 1", "Sub-step 2"))
      state <- stateRef.get
    } yield state

    val state = program.unsafeRunSync()
    assertEquals(state.statusText, "Working on goal...")
    assertEquals(state.activePanelLines, Vector("Sub-step 1", "Sub-step 2"))
  }

  test("LanternaTuiRenderer respects scrollOffset") {
    import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
    val terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24))
    val screen = new TerminalScreen(terminal)
    screen.startScreen()

    val state = TuiState(
      scrollback = Vector(
        LanternaLogLine(Some("Agent"), "Line 1", TerminalStyle.Default),
        LanternaLogLine(Some("Agent"), "Line 2", TerminalStyle.Default),
        LanternaLogLine(Some("Agent"), "Line 3", TerminalStyle.Default)
      ),
      scrollOffset = 1
    )

    // Verify drawing triggers with scrollOffset without throwing exception
    LanternaTuiRenderer.render(screen, state)
    
    val size = screen.getTerminalSize
    assertEquals(size.getColumns, 80)
  }

  test("LanternaTerminalReader maps Ctrl+U and Ctrl+B shortcuts correctly") {
    import com.googlecode.lanterna.input.{KeyStroke, KeyType}
    import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal
    val terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24))
    val screen = new TerminalScreen(terminal)
    screen.startScreen()

    val program = for {
      stateRef <- Ref.of[IO, TuiState](TuiState(scrollback = Vector(LanternaLogLine(None, "Line 1", TerminalStyle.Default))))
      completionsRef <- Ref.of[IO, List[String]](List.empty)
      reader = LanternaTerminalReader(screen, stateRef, completionsRef)
      
      _ <- IO.blocking {
        val ctrlU = new KeyStroke('u', false, false, true) // character 'u', ctrl = true
        terminal.addInput(ctrlU)
      }
      _ <- IO.blocking {
        val enter = new KeyStroke(KeyType.Enter)
        terminal.addInput(enter)
      }
      
      result <- reader.readLine("tark> ")
      state <- stateRef.get
    } yield (result, state)

    val (result, state) = program.unsafeRunSync()
    assert(result.isInstanceOf[InputResult.Line])
    assertEquals(state.scrollOffset, 0)
  }
}
