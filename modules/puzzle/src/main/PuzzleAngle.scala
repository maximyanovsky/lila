package lila.puzzle

import play.api.i18n.Lang

import lila.common.{ LilaOpeningFamily, SimpleOpening }
import lila.i18n.I18nKey
import chess.opening.{ FullOpening, FullOpeningDB }

sealed abstract class PuzzleAngle(val key: String) {
  val name: I18nKey
  def description: I18nKey
  def asTheme: Option[PuzzleTheme.Key]
  def opening: Option[FullOpening]
}

object PuzzleAngle {
  case class Theme(theme: PuzzleTheme.Key) extends PuzzleAngle(theme.value) {
    val name        = PuzzleTheme(theme).name
    val description = PuzzleTheme(theme).description
    def asTheme     = theme.some
    def opening     = none
  }
  case class Opening(either: Either[LilaOpeningFamily.Key, SimpleOpening.Key])
      extends PuzzleAngle(either.fold(_.value, _.value)) {
    def openingName = either.fold(
      k => LilaOpeningFamily(k).map(_.name.value),
      k => SimpleOpening(k).map(_.name.value)
    ) | "Any"
    def opening = either
      .fold(
        _.value.some,
        opKey => SimpleOpening(opKey).map(_.ref.key)
      ) flatMap FullOpeningDB.shortestLines.get
    val name        = new I18nKey(openingName)
    def description = new I18nKey(s"From games with the opening: $openingName")
    def asTheme     = none
  }

  // def apply(theme: PuzzleTheme.Key): PuzzleAngle = Theme(theme)
  def apply(theme: PuzzleTheme): PuzzleAngle = Theme(theme.key)
  // def apply(opening: SimpleOpening.Key): PuzzleAngle = Opening(opening)
  def apply(family: LilaOpeningFamily): PuzzleAngle = Opening(Left(family.key))
  def apply(opening: SimpleOpening): PuzzleAngle    = Opening(Right(opening.key))

  def find(key: String): Option[PuzzleAngle] =
    PuzzleTheme
      .find(key)
      .map(apply)
      .orElse(LilaOpeningFamily.find(key).map(apply))
      .orElse(SimpleOpening.find(key).map(apply))

  val mix: PuzzleAngle = apply(PuzzleTheme.mix)

  def findOrMix(key: String): PuzzleAngle = find(key) | mix

  case class All(
      themes: List[(lila.i18n.I18nKey, List[PuzzleTheme.WithCount])],
      openings: PuzzleOpeningCollection
  )

  implicit val angleIso = lila.common.Iso.string[PuzzleAngle](findOrMix, _.key)
}
