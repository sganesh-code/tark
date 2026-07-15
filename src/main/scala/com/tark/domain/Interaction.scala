package com.tark.domain

case class Interaction(
                      id: String,
                      input: String,
                      output: String,
                      timestamp: Long,
                      toolName: String
                      )
