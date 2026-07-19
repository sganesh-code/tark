package com.tark.domain

import io.circe.{Encoder, Decoder}
import io.circe.generic.semiauto.*

case class Config(
                 modelId: String,
                 maxTokens: Int,
                 baseUrl: String,
                 sandboxImageName: String,
                 panelWidth: Int = 80,
                 panelBorder: String = "rounded",
                 contextWindowSize: Int = 32768,
                 enableDistillation: Boolean = true,
                 distillationThreshold: Int = 8000
                 )

object Config {
  val DefaultModelId = "qwen3-coder:30b"
  val DefaultMaxTokens = 2048
  val DefaultBaseUrl = "http://localhost:11434/v1/chat/completions"
  val DefaultSandboxImageName = "tark-sandbox:latest"
  val DefaultContextWindowSize = 32768
  val DefaultEnableDistillation = true
  val DefaultDistillationThreshold = 8000

  val default: Config = Config(
    modelId = DefaultModelId,
    maxTokens = DefaultMaxTokens,
    baseUrl = DefaultBaseUrl,
    sandboxImageName = DefaultSandboxImageName,
    panelWidth = 80,
    panelBorder = "rounded",
    contextWindowSize = DefaultContextWindowSize,
    enableDistillation = DefaultEnableDistillation,
    distillationThreshold = DefaultDistillationThreshold
  )

  given Encoder[Config] = deriveEncoder
  given Decoder[Config] = deriveDecoder
}
