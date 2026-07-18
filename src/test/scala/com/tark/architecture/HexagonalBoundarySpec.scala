package com.tark.architecture

import munit.FunSuite
import java.io.File
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

class HexagonalBoundarySpec extends FunSuite {

  // Helper to recursively find all .scala files under a directory
  def findScalaFiles(dir: File): List[File] = {
    if (!dir.exists()) Nil
    else {
      val files = dir.listFiles()
      if (files == null) Nil
      else {
        val (dirs, scalaFiles) = files.partition(_.isDirectory)
        scalaFiles.filter(_.getName.endsWith(".scala")).toList ++ dirs.flatMap(findScalaFiles)
      }
    }
  }

  test("Hexagonal Architecture: Boundary and Dependency Rules") {
    val srcRoot = new File("src/main/scala/com/tark")
    val scalaFiles = findScalaFiles(srcRoot)

    assert(scalaFiles.nonEmpty, "No Scala source files found to validate!")

    val violations = ListBuffer.empty[String]

    for (file <- scalaFiles) {
      val pathStr = file.getAbsolutePath.replace('\\', '/')
      
      // Determine layer from file path
      val layerOpt = if (pathStr.contains("/com/tark/domain/")) Some("domain")
                     else if (pathStr.contains("/com/tark/ui/")) Some("ui")
                     else if (pathStr.contains("/com/tark/application/")) Some("application")
                     else if (pathStr.contains("/com/tark/ports/")) Some("ports")
                     else if (pathStr.contains("/com/tark/adapters/")) Some("adapters")
                     else if (pathStr.contains("/com/tark/bootstrap/")) Some("bootstrap")
                     else None

      layerOpt.foreach { layer =>
        val lines = Files.readAllLines(file.toPath).asScala.toList
        for ((line, lineNum) <- lines.zipWithIndex) {
          val trimmed = line.trim
          if (trimmed.startsWith("import com.tark.")) {
            // Extract the imported package/type
            val imported = trimmed.substring("import ".length).trim

            layer match {
              case "domain" =>
                // 1. com.tark.domain must not import com.tark.ports, com.tark.adapters, com.tark.application, or com.tark.bootstrap.
                if (imported.startsWith("com.tark.ports") ||
                    imported.startsWith("com.tark.adapters") ||
                    imported.startsWith("com.tark.application") ||
                    imported.startsWith("com.tark.bootstrap")) {
                  violations += s"[Layer Violation] domain file $file:${lineNum + 1} imports '$imported'"
                }

              case "application" =>
                // 2. com.tark.application must not import com.tark.adapters or com.tark.bootstrap.
                if (imported.startsWith("com.tark.adapters") ||
                    imported.startsWith("com.tark.bootstrap")) {
                  violations += s"[Layer Violation] application file $file:${lineNum + 1} imports '$imported'"
                }

              case "ui" =>
                // 2a. com.tark.ui is portable frontend language and must not import concrete layers.
                if (imported.startsWith("com.tark.adapters") ||
                    imported.startsWith("com.tark.bootstrap") ||
                    imported.startsWith("com.tark.application")) {
                  violations += s"[Layer Violation] ui file $file:${lineNum + 1} imports '$imported'"
                }

              case "ports" =>
                // 3. com.tark.ports must not import com.tark.adapters or com.tark.bootstrap.
                if (imported.startsWith("com.tark.adapters") ||
                    imported.startsWith("com.tark.bootstrap")) {
                  violations += s"[Layer Violation] ports file $file:${lineNum + 1} imports '$imported'"
                }

              case "adapters" =>
                // 4. com.tark.adapters must not import com.tark.bootstrap
                if (imported.startsWith("com.tark.bootstrap")) {
                  violations += s"[Layer Violation] adapters file $file:${lineNum + 1} imports '$imported'"
                }

                // 5. Adapters must not import each other across unrelated technologies.
                // Determine sub-package (technology) of the current adapter file
                val currentSubPackage = getAdapterSubPackage(pathStr)
                currentSubPackage.foreach { currentTech =>
                  if (imported.startsWith("com.tark.adapters.")) {
                    val importedSubOpt = getAdapterSubPackageFromImport(imported)
                    importedSubOpt.foreach { importedTech =>
                      if (currentTech != importedTech && !isAllowedAdapterDependency(currentTech, importedTech)) {
                        violations += s"[Adapter Tech Violation] adapter ($currentTech) file $file:${lineNum + 1} imports '$imported' ($importedTech)"
                      }
                    }
                  }
                }

              case _ => // bootstrap is allowed to import everything
            }
          }
        }
      }
    }

    if (violations.nonEmpty) {
      val message = "Hexagonal architecture boundary violations found:\n" + violations.mkString("\n")
      fail(message)
    }
  }

  private def getAdapterSubPackage(pathStr: String): Option[String] = {
    // Path looks like: src/main/scala/com/tark/adapters/backend/ollama/...
    val marker = "/com/tark/adapters/"
    val idx = pathStr.indexOf(marker)
    if (idx != -1) {
      val rest = pathStr.substring(idx + marker.length)
      val parts = rest.split('/')
      if (parts.length > 1) {
        // e.g. "backend/ollama" or "sandbox/docker" or "context" or "ui"
        if (parts(0) == "backend" && parts.length > 2) Some(s"backend/${parts(1)}")
        else if (parts(0) == "sandbox" && parts.length > 2) Some(s"sandbox/${parts(1)}")
        else if (parts(0) == "tool" && parts.length > 2) Some(s"tool/${parts(1)}")
        else if (parts(0) == "inbound" && parts.length > 2) Some(s"inbound/${parts(1)}")
        else Some(parts(0))
      } else if (parts.length == 1 && parts(0).nonEmpty) {
        Some(parts(0))
      } else None
    } else None
  }

  private def getAdapterSubPackageFromImport(importedStr: String): Option[String] = {
    // Import looks like: com.tark.adapters.sandbox.docker.DockerSandbox
    val prefix = "com.tark.adapters."
    if (importedStr.startsWith(prefix)) {
      val rest = importedStr.substring(prefix.length)
      val parts = rest.split('.')
      if (parts.length > 1) {
        if (parts(0) == "backend" && parts.length > 2) Some(s"backend/${parts(1)}")
        else if (parts(0) == "sandbox" && parts.length > 2) Some(s"sandbox/${parts(1)}")
        else if (parts(0) == "tool" && parts.length > 2) Some(s"tool/${parts(1)}")
        else if (parts(0) == "inbound" && parts.length > 2) Some(s"inbound/${parts(1)}")
        else Some(parts(0))
      } else if (parts.length == 1 && parts(0).nonEmpty) {
        Some(parts(0))
      } else None
    } else None
  }

  private def isAllowedAdapterDependency(currentTech: String, importedTech: String): Boolean = {
    // Define explicit allowed dependencies between adapter technologies:
    // - tool/command is allowed to use sandbox/docker and sandbox/local
    // - context is allowed to use tool/command and sandbox/docker
    (currentTech == "tool/command" && (importedTech == "sandbox/docker" || importedTech == "sandbox/local")) ||
    (currentTech == "context" && (importedTech == "tool/command" || importedTech == "sandbox/docker"))
  }
}
