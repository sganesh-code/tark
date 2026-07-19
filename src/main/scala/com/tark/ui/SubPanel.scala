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
  borderStyle: BorderStyle = BorderStyle.Rounded,
  maxLines: Int = 10
)

final case class PanelState(
  config: PanelConfig,
  contentLines: Vector[String] = Vector.empty
)

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
          truncatedLines.map(_.padTo(config.width, ' '))
        case Some(bc) =>
          val horizontalBar = bc.horizontal.toString * innerWidth
          val topBorder = s"${bc.topLeft}$horizontalBar${bc.topRight}"
          val bottomBorder = s"${bc.bottomLeft}$horizontalBar${bc.bottomRight}"
          val contentRows = truncatedLines.map { line =>
            s"${bc.vertical}${line.padTo(innerWidth, ' ')}${bc.vertical}"
          }
          topBorder +: contentRows :+ bottomBorder

    private def wrap(text: String, width: Int): Vector[String] =
      if width <= 0 then Vector(text)
      else
        val rawLines = text.split("\n", -1).toVector
        rawLines.flatMap { line =>
          val words = line.split(" ")
          val (wrapped, last) = words.foldLeft((Vector.empty[String], "")) { case ((acc, currentLine), word) =>
            if currentLine.isEmpty then
              if word.length > width then
                val chunks = word.grouped(width).toVector
                ((acc ++ chunks.init): Vector[String], chunks.last)
              else (acc, word)
            else
              val potential = s"$currentLine $word"
              if potential.length > width then
                if word.length > width then
                  val chunks = word.grouped(width).toVector
                  (((acc :+ currentLine) ++ chunks.init): Vector[String], chunks.last)
                else (acc :+ currentLine, word)
              else (acc, potential)
          }
          if last.isEmpty then wrapped else wrapped :+ last
        }
