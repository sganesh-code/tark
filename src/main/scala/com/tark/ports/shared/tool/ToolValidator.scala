package com.tark.ports.shared.tool

import com.tark.domain.tool.ToolDefinition
import io.circe.Json

object ToolValidator {
  /**
   * Purely functional validation of a JSON input payload against a ToolDefinition's parameter schema.
   */
  def validate(definition: ToolDefinition, input: Json): Either[String, Unit] = {
    input.asObject match {
      case None => Left("Input must be a JSON object")
      case Some(obj) =>
        val params = definition.function.parameters
        val checkFields = params.properties.map { case (fieldName, prop) =>
          obj.apply(fieldName) match {
            case None =>
              if (params.required.contains(fieldName)) Left(s"Missing required field: $fieldName")
              else Right(())
            case Some(value) =>
              prop.`type` match {
                case "string" =>
                  if (value.isString) Right(())
                  else Left(s"Field '$fieldName' must be a string")
                case "integer" | "number" =>
                  if (value.isNumber) Right(())
                  else Left(s"Field '$fieldName' must be a number")
                case "boolean" =>
                  if (value.isBoolean) Right(())
                  else Left(s"Field '$fieldName' must be a boolean")
                case _ =>
                  Right(()) // Fallback for other or unknown types
              }
          }
        }
        
        checkFields.collectFirst { case Left(err) => Left(err) }.getOrElse(Right(()))
    }
  }
}
