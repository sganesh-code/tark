package com.tark

import cats.effect.*
import com.tark.bootstrap.TarkApp

object TarkCLI extends IOApp.Simple {
  override def run: IO[Unit] = TarkApp.run
}
