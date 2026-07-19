package com.tark.domain

import io.circe.{Encoder, Decoder}
import io.circe.generic.semiauto.*

case class Config(
                 modelId: String,
                 maxTokens: Int,
                 baseUrl: String,
                 sandboxImageName: String,
                 panelWidth: Int = 80,
                 panelBorder: String = "rounded"
                 )

object Config {
  val DefaultModelId = "qwen3-coder:30b"
  val DefaultMaxTokens = 2048
  val DefaultBaseUrl = "http://localhost:11434/v1/chat/completions"
  val DefaultSandboxImageName = "tark-sandbox:latest"

  val default: Config = Config(
    modelId = DefaultModelId,
    maxTokens = DefaultMaxTokens,
    baseUrl = DefaultBaseUrl,
    sandboxImageName = DefaultSandboxImageName,
    panelWidth = 80,
    panelBorder = "rounded"
  )

  given Encoder[Config] = deriveEncoder
  given Decoder[Config] = deriveDecoder
}
