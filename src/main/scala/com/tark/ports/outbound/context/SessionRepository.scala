package com.tark.ports.outbound.context

import com.tark.domain.memory.Memory
import java.nio.file.Path

trait SessionRepository[F[_]] {
  def loadLatestMemory(directory: Path): F[Memory]
}
