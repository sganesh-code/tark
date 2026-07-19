package com.tark.domain.tool

import io.circe.syntax.*
import munit.FunSuite

class QuestionnaireToolSpec extends FunSuite {
  test("QuestionnaireTool definition is serialized correctly as custom flat JSON") {
    val definition = QuestionnaireTool.definition
    val json = definition.asJson

    // The parameters field should be exactly our Custom JSON schema, not wrapped in a "Custom" tag
    val parametersCursor = json.hcursor.downField("function").downField("parameters")
    
    assertEquals(parametersCursor.get[String]("type"), Right("object"))
    assert(parametersCursor.downField("properties").downField("question").focus.isDefined)
    assert(parametersCursor.downField("properties").downField("options").focus.isDefined)
    
    // There shouldn't be any "Custom" wrapping
    assert(parametersCursor.downField("Custom").focus.isEmpty)
  }

  test("QuestionnaireTool parseArguments extracts valid parameters with 'question'") {
    val args = """{"question": "What is your favorite color?", "options": ["Red", "Green", "Blue"]}"""
    val result = QuestionnaireTool.parseArguments(args)
    
    assertEquals(result, Right(("What is your favorite color?", List("Red", "Green", "Blue"))))
  }

  test("QuestionnaireTool parseArguments supports fallback parameter names 'prompt' and 'questionnaire'") {
    val argsPrompt = """{"prompt": "Select an environment:", "options": ["Dev", "Prod"]}"""
    val argsQuestionnaire = """{"questionnaire": "Select role:", "options": ["Admin", "User"]}"""
    
    assertEquals(QuestionnaireTool.parseArguments(argsPrompt), Right(("Select an environment:", List("Dev", "Prod"))))
    assertEquals(QuestionnaireTool.parseArguments(argsQuestionnaire), Right(("Select role:", List("Admin", "User"))))
  }

  test("QuestionnaireTool parseArguments rejects missing question") {
    val args = """{"options": ["A", "B"]}"""
    val result = QuestionnaireTool.parseArguments(args)
    
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("missing or empty"))
  }

  test("QuestionnaireTool parseArguments rejects missing options") {
    val args = """{"question": "How are you?"}"""
    val result = QuestionnaireTool.parseArguments(args)
    
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("options' is missing"))
  }

  test("QuestionnaireTool parseArguments rejects empty options list") {
    val args = """{"question": "How are you?", "options": []}"""
    val result = QuestionnaireTool.parseArguments(args)
    
    assert(result.isLeft)
    assert(result.left.toOption.get.contains("at least one option"))
  }
}
