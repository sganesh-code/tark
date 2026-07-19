package com.tark.bootstrap

import cats.effect.IO
import com.tark.domain.Config

case class RuntimeConfig(config: Config, forceBuild: Boolean)

object RuntimeConfig {
  val DefaultForceBuild = false

  def fromEnvironment: IO[RuntimeConfig] =
    IO(sys.env).map(fromEnv)

  def fromEnv(env: Map[String, String]): RuntimeConfig = {
    val config = Config(
      modelId = env.getOrElse("TARK_OLLAMA_MODEL", Config.DefaultModelId),
      maxTokens = env.get("TARK_MAX_TOKENS").flatMap(_.toIntOption).getOrElse(Config.DefaultMaxTokens),
      baseUrl = env.getOrElse("TARK_OLLAMA_URL", Config.DefaultBaseUrl),
      sandboxImageName = env.getOrElse("TARK_SANDBOX_IMAGE", Config.DefaultSandboxImageName),
      panelWidth = env.get("TARK_PANEL_WIDTH").flatMap(_.toIntOption).getOrElse(80),
      panelBorder = env.getOrElse("TARK_PANEL_BORDER", "rounded"),
      contextWindowSize = env.get("TARK_CONTEXT_WINDOW_SIZE").flatMap(_.toIntOption).getOrElse(Config.DefaultContextWindowSize),
      enableDistillation = env.get("TARK_ENABLE_DISTILLATION").flatMap(_.toBooleanOption).getOrElse(Config.DefaultEnableDistillation),
      distillationThreshold = env.get("TARK_DISTILLATION_THRESHOLD").flatMap(_.toIntOption).getOrElse(Config.DefaultDistillationThreshold)
    )

    RuntimeConfig(
      config = config,
      forceBuild = env.get("TARK_FORCE_BUILD").flatMap(_.toBooleanOption).getOrElse(DefaultForceBuild)
    )
  }
}
