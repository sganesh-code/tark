package com.tark.ui

enum BorderStyle:
  case None
  case Single      // ┌ ─ ┐ │ └ ┘
  case Double      // ╔ ═ ╗ ║ ╚ ╝
  case Rounded     // ╭ ─ ╮ │ ╰ ╯
  case Ascii       // + - + | + +

object BorderStyle:
  def fromString(s: String): BorderStyle = s.toLowerCase match
    case "single"  => Single
    case "double"  => Double
    case "ascii"   => Ascii
    case "none"    => None
    case _         => Rounded

private[ui] case class BorderChars(
  topLeft: Char,
  topRight: Char,
  bottomLeft: Char,
  bottomRight: Char,
  horizontal: Char,
  vertical: Char
)

object BorderChars:
  def getChars(style: BorderStyle): Option[BorderChars] = style match
    case BorderStyle.None    => scala.None
    case BorderStyle.Single  => Some(BorderChars('┌', '┐', '└', '┘', '─', '│'))
    case BorderStyle.Double  => Some(BorderChars('╔', '╗', '╚', '╝', '═', '║'))
    case BorderStyle.Rounded => Some(BorderChars('╭', '╮', '╰', '╯', '─', '│'))
    case BorderStyle.Ascii   => Some(BorderChars('+', '+', '+', '+', '-', '|'))

final case class PanelConfig(
  width: Int,
  borderStyle: BorderStyle = BorderStyle.Single,
  maxLines: Int = 10
)

final case class PanelState(
  config: PanelConfig,
  contentLines: Vector[String] = Vector.empty
)

private[ui] def stripAnsi(s: String): String =
  s.replaceAll("\\u001b\\[[;\\d]*[ -/]*[@-~]", "")

trait PanelRenderer[A]:
  def render(state: A): Vector[String]

object PanelRenderer:
  given panelStateRenderer: PanelRenderer[PanelState] with
    override def render(state: PanelState): Vector[String] =
      val config = state.config
      val borderOpt = BorderChars.getChars(config.borderStyle)
      val hasBorder = borderOpt.isDefined
      val innerWidth = if hasBorder then config.width - 2 else config.width

      val wrappedLines = state.contentLines.flatMap(line => wrap(line, innerWidth))
      val truncatedLines = wrappedLines.takeRight(config.maxLines)

      borderOpt match
        case scala.None =>
          truncatedLines.map { line =>
            val visibleLen = stripAnsi(line).length
            val padAmount = Math.max(0, config.width - visibleLen)
            line + (" " * padAmount) + "\u001b[0m"
          }
        case Some(bc) =>
          val horizontalBar = bc.horizontal.toString * innerWidth
          val topBorder = s"${bc.topLeft}$horizontalBar${bc.topRight}"
          val bottomBorder = s"${bc.bottomLeft}$horizontalBar${bc.bottomRight}"
          val contentRows = truncatedLines.map { line =>
            val visibleLen = stripAnsi(line).length
            val padAmount = Math.max(0, innerWidth - visibleLen)
            val padded = line + (" " * padAmount)
            s"${bc.vertical}$padded\u001b[0m${bc.vertical}"
          }
          topBorder +: contentRows :+ bottomBorder

    private def wrap(text: String, width: Int): Vector[String] =
      if width <= 0 then Vector(text)
      else
        val rawLines = text.split("\n", -1).toVector
        rawLines.flatMap { line =>
          if line.isEmpty then Vector("")
          else
            val words = line.split(" ")
            val (wrapped, last) = words.foldLeft((Vector.empty[String], "")) { case ((acc, currentLine), word) =>
              val wordLen = stripAnsi(word).length
              val currentLineLen = stripAnsi(currentLine).length
              if currentLine.isEmpty then
                if wordLen > width then
                  val chunks = word.grouped(width).toVector
                  (acc ++ chunks.init: Vector[String], chunks.last)
                else (acc, word)
              else
                val potential = s"$currentLine $word"
                val potentialLen = currentLineLen + 1 + wordLen
                if potentialLen > width then
                  if wordLen > width then
                    val chunks = word.grouped(width).toVector
                    ((acc :+ currentLine) ++ chunks.init: Vector[String], chunks.last)
                  else (acc :+ currentLine, word)
                else (acc, potential)
            }
            if last.isEmpty then wrapped else wrapped :+ last
        }
