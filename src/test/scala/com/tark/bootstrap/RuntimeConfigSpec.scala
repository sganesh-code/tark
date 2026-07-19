package com.tark.bootstrap

import com.tark.domain.Config
import munit.FunSuite

class RuntimeConfigSpec extends FunSuite {
  test("RuntimeConfig: uses pure domain defaults when environment is empty") {
    val runtimeConfig = RuntimeConfig.fromEnv(Map.empty)

    assertEquals(runtimeConfig.config, Config.default)
    assertEquals(runtimeConfig.forceBuild, false)
  }

  test("RuntimeConfig: reads supported environment overrides") {
    val runtimeConfig = RuntimeConfig.fromEnv(
      Map(
        "TARK_OLLAMA_MODEL" -> "custom-model",
        "TARK_MAX_TOKENS" -> "4096",
        "TARK_OLLAMA_URL" -> "http://ollama.example/v1/chat/completions",
        "TARK_SANDBOX_IMAGE" -> "custom-sandbox:latest",
        "TARK_FORCE_BUILD" -> "true",
        "TARK_CONTEXT_WINDOW_SIZE" -> "16384",
        "TARK_ENABLE_DISTILLATION" -> "false",
        "TARK_DISTILLATION_THRESHOLD" -> "500"
      )
    )

    assertEquals(runtimeConfig.config.modelId, "custom-model")
    assertEquals(runtimeConfig.config.maxTokens, 4096)
    assertEquals(runtimeConfig.config.baseUrl, "http://ollama.example/v1/chat/completions")
    assertEquals(runtimeConfig.config.sandboxImageName, "custom-sandbox:latest")
    assertEquals(runtimeConfig.config.contextWindowSize, 16384)
    assertEquals(runtimeConfig.config.enableDistillation, false)
    assertEquals(runtimeConfig.config.distillationThreshold, 500)
    assertEquals(runtimeConfig.forceBuild, true)
  }

  test("RuntimeConfig: falls back for invalid numeric and boolean values") {
    val runtimeConfig = RuntimeConfig.fromEnv(
      Map(
        "TARK_MAX_TOKENS" -> "not-a-number",
        "TARK_FORCE_BUILD" -> "not-a-boolean",
        "TARK_CONTEXT_WINDOW_SIZE" -> "not-a-number",
        "TARK_ENABLE_DISTILLATION" -> "not-a-boolean",
        "TARK_DISTILLATION_THRESHOLD" -> "not-a-number"
      )
    )

    assertEquals(runtimeConfig.config.maxTokens, Config.DefaultMaxTokens)
    assertEquals(runtimeConfig.config.contextWindowSize, Config.DefaultContextWindowSize)
    assertEquals(runtimeConfig.config.enableDistillation, Config.DefaultEnableDistillation)
    assertEquals(runtimeConfig.config.distillationThreshold, Config.DefaultDistillationThreshold)
    assertEquals(runtimeConfig.forceBuild, RuntimeConfig.DefaultForceBuild)
  }
}
