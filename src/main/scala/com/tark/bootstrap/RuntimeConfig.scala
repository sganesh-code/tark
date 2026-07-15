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
      sandboxImageName = env.getOrElse("TARK_SANDBOX_IMAGE", Config.DefaultSandboxImageName)
    )

    RuntimeConfig(
      config = config,
      forceBuild = env.get("TARK_FORCE_BUILD").flatMap(_.toBooleanOption).getOrElse(DefaultForceBuild)
    )
  }
}
