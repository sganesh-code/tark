package com.tark.ports.shared.config

trait ConfigOps[C] {
  def getModelId(config: C): String
  def getMaxTokens(config: C): Int
  def getBaseUrl(config: C): String
  def getSandboxImageName(config: C): String
  def withUpdatedConfig(config: C, updates: Map[String, Any]): C
}
