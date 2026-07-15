package com.tark.domain

import com.tark.domain.context.{Context, Session}
import munit.FunSuite

import java.nio.file.Paths

class SessionSpec extends FunSuite {
  test("Session: can be initialized and associated with Context") {
    val context = Context(Map.empty, Map.empty, List.empty)
    val session = Session("test_session_123", context, Paths.get("target/test.md"))

    assertEquals(session.id, "test_session_123")
    assertEquals(session.context, context)
  }
}
