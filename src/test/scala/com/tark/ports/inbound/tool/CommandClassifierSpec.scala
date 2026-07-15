package com.tark.ports.inbound.tool

import munit.FunSuite

class CommandClassifierSpec extends FunSuite {
  test("CommandClassifier: correctly classifies SLASH_COMMAND, HARNESS_COMMAND, and REACT_COMMAND") {
    assertEquals(CommandClassifier.classify("/help"), SLASH_COMMAND)
    assertEquals(CommandClassifier.classify("/exit"), SLASH_COMMAND)
    assertEquals(CommandClassifier.classify("/clear"), SLASH_COMMAND)
    assertEquals(CommandClassifier.classify("/run ls -la"), HARNESS_COMMAND)
    assertEquals(CommandClassifier.classify("/run"), HARNESS_COMMAND)
    assertEquals(CommandClassifier.classify("What is the capital of France?"), REACT_COMMAND)
  }
}
