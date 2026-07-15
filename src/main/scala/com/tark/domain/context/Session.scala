package com.tark.domain.context

import java.nio.file.Path

case class Session(
                  id: String,
                  context: Context,
                  sessionPath: Path
                  )
