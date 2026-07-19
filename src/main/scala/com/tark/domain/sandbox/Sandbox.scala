package com.tark.domain.sandbox

import java.io.File

trait Sandbox {
  def name: String
  def buildProcess(command: String): (Seq[String], Option[File])
}
