package com.tark.ports.shared.ui

import com.tark.application.instances.all.given
import com.tark.domain.ui.{Cell, Color, Screen, Style}
import munit.FunSuite

class LayoutSpec extends FunSuite {
  private def screenSnapshot(screen: Screen): Vector[Vector[Cell]] =
    (0 until screen.height).map { y =>
      (0 until screen.width).map(x => screen.cell(x, y)).toVector
    }.toVector

  test("Layout: correctly structures chat state into the screen layout") {
    val state = ChatState(
      history = Vector(Message.System("Hello!"), Message.System("Welcome to Tark")),
      prompt = "testing"
    )

    val width = 40
    val height = 10
    val screen = summon[Layout[ChatState]].render(state, width, height)

    assertEquals(screen.width, width)
    assertEquals(screen.height, height)
    assertEquals(screen.cell(0, 0).glyph, "╭")

    val headerRowStr = (0 until width).map(screen.cell(_, 1).glyph).mkString
    assert(headerRowStr.contains("Tark CLI"))

    val firstMsgRow = (0 until width).map(screen.cell(_, 2).glyph).mkString
    assert(firstMsgRow.contains("Hello!"))
    assert(firstMsgRow.startsWith("│ "))
    assert(firstMsgRow.endsWith(" │"))

    val secondMsgRow = (0 until width).map(screen.cell(_, 3).glyph).mkString
    assert(secondMsgRow.contains("Welcome to Tark"))

    assertEquals(screen.cell(0, height - 3).glyph, "├")

    val promptRow = (0 until width).map(screen.cell(_, height - 2).glyph).mkString
    assert(promptRow.contains("❯ testing"))
    assertEquals(screen.cell(0, height - 1).glyph, "╰")
  }

  test("Layout laws: rendering is deterministic and does not mutate ChatState") {
    val state = ChatState(
      history = Vector(Message.User("Hello"), Message.AI("Hi"), Message.System("Running")),
      prompt = "testing",
      scrollOffset = 1,
      currentThought = Some("Thinking")
    )
    val before = state.copy(history = state.history)

    val first = summon[Layout[ChatState]].render(state, 48, 12)
    val second = summon[Layout[ChatState]].render(state, 48, 12)

    assertEquals(first.width, 48)
    assertEquals(first.height, 12)
    assertEquals(screenSnapshot(first), screenSnapshot(second))
    assertEquals(state, before)
  }

  test("Layout laws: wrapping and rendering preserve visible message styles") {
    val message = Message.User(
      "This message wraps across several rows",
      fg = Color.BrightYellow,
      bg = Color.Black,
      styles = Set(Style.Bold)
    )
    val wrapped = Layout.wrapMessages(Vector(message), 12)

    assert(wrapped.length > 1)
    wrapped.foreach { line =>
      assertEquals(line.fg, Color.BrightYellow)
      assertEquals(line.bg, Color.Black)
      assertEquals(line.styles, Set(Style.Bold))
    }

    val screen = summon[Layout[ChatState]].render(ChatState(Vector(message), ""), 18, 12)
    val visibleStyledCells =
      for {
        y <- 2 until 6
        x <- 2 until 16
        cell = screen.cell(x, y)
        if cell.glyph.trim.nonEmpty
      } yield cell

    assert(visibleStyledCells.nonEmpty)
    visibleStyledCells.foreach { cell =>
      assertEquals(cell.fg, Color.BrightYellow)
      assertEquals(cell.bg, Color.Black)
      assertEquals(cell.styles, Set(Style.Bold))
    }
  }

  test("Layout: correctly applies color codes to You, AI, and System lines") {
    val state = ChatState(
      history = Vector(Message.User("Hello"), Message.AI("Hi"), Message.System("Running")),
      prompt = "testing"
    )
    val width = 40
    val height = 12
    val screen = summon[Layout[ChatState]].render(state, width, height)

    assertEquals(screen.cell(2, 2).glyph, "H")
    assertEquals(screen.cell(2, 2).fg, Color.BrightGreen)
    assertEquals(screen.cell(2, 3).glyph, "H")
    assertEquals(screen.cell(2, 3).fg, Color.BrightBlue)
    assertEquals(screen.cell(2, 4).glyph, "R")
    assertEquals(screen.cell(2, 4).fg, Color.BrightRed)
  }

  test("Layout: successfully renders currentThought inside the Thought Row") {
    val state = ChatState(
      history = Vector(Message.System("Hello!")),
      prompt = "testing",
      currentThought = Some("I am thinking about step 1")
    )
    val width = 40
    val height = 10
    val screen = summon[Layout[ChatState]].render(state, width, height)

    val thoughtRowStr = (0 until width).map(screen.cell(_, 4).glyph).mkString
    assert(thoughtRowStr.contains("(...)I am thinking about step 1"))
    assert(thoughtRowStr.startsWith("│ "))
    assert(thoughtRowStr.endsWith(" │"))
  }

  test("Layout: successfully supports scrolling via scrollOffset") {
    val state = ChatState(
      history = (1 to 20).map(i => Message.System(s"Message $i")).toVector,
      prompt = "testing",
      scrollOffset = 5
    )
    val width = 40
    val height = 12
    val screen = summon[Layout[ChatState]].render(state, width, height)

    val lastHistoryRowStr = (0 until width).map(screen.cell(_, 5).glyph).mkString
    assert(lastHistoryRowStr.contains("Message 15"))
    assert(!lastHistoryRowStr.contains("Message 20"))
  }

  test("Layout.wrapText: correctly wraps long lines and handles explicit newlines") {
    val text = "This is a very long line that should be wrapped into multiple lines because it exceeds the maximum width allowed for line rendering."
    val wrapped = Layout.wrapText(text, 20)

    wrapped.foreach { line =>
      assert(line.length <= 20, s"Line too long: '$line' (${line.length} chars)")
    }

    assert(wrapped.contains("This is a very long"))
    assert(wrapped.contains("line that should be"))

    val newlineText = "Line 1\nLine 2 is longer than maxWidth"
    val wrappedNewlines = Layout.wrapText(newlineText, 15)
    assert(wrappedNewlines.contains("Line 1"))
    assert(wrappedNewlines.contains("Line 2 is"))
  }

  test("ChatState transition: handles slash commands correctly") {
    val initial = ChatState(Vector(Message.System("Hello")), "test message")

    val afterNormal = ChatState.transition(initial).get
    assertEquals(afterNormal.history, Vector(Message.System("Hello"), Message.User("test message")))
    assertEquals(afterNormal.prompt, "")

    val helpState = ChatState(Vector(Message.System("Hello")), "/help")
    val afterHelp = ChatState.transition(helpState).get
    assertEquals(afterHelp.history, Vector(Message.System("Hello"), Message.User("/help"), Message.System("Available commands: /help, /clear, /memory, /run <command>, /exit")))
    assertEquals(afterHelp.prompt, "")

    val clearState = ChatState(Vector(Message.System("Hello"), Message.System("World")), "/clear")
    val afterClear = ChatState.transition(clearState).get
    assertEquals(afterClear.history, Vector.empty[Message])
    assertEquals(afterClear.prompt, "")

    val exitState = ChatState(Vector(Message.System("Hello")), "/exit")
    val afterExit = ChatState.transition(exitState)
    assertEquals(afterExit, None)

    val unknownState = ChatState(Vector(Message.System("Hello")), "/invalid")
    val afterUnknown = ChatState.transition(unknownState).get
    assertEquals(afterUnknown.history, Vector(Message.System("Hello"), Message.User("/invalid"), Message.System("Unknown command: /invalid")))
    assertEquals(afterUnknown.prompt, "")
  }
}
