package com.tark.application.instances

import com.tark.domain.Config
import com.tark.ports.shared.config.ConfigOps

object ConfigInstances {
  given ConfigOps[Config] with {

    override def getModelId(config: Config): String =
      config.modelId

    override def getMaxTokens(config: Config): Int =
      config.maxTokens

    override def getBaseUrl(config: Config): String =
      config.baseUrl

    override def getSandboxImageName(config: Config): String =
      config.sandboxImageName

    override def withUpdatedConfig(config: Config, updates: Map[String, Any]): Config =
      config.copy(
        modelId = updates.get("modelId").map(_.toString).getOrElse(config.modelId),
        maxTokens = updates.get("maxTokens").map(_.asInstanceOf[Int]).getOrElse(config.maxTokens),
        baseUrl = updates.get("baseUrl").map(_.toString).getOrElse(config.baseUrl),
        sandboxImageName = updates.get("sandboxImageName").map(_.toString).getOrElse(config.sandboxImageName)
      )
  }
}
