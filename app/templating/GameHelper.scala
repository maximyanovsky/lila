package lila.app
package templating

import chess.{ Black, Clock, Color, Mode, Outcome, Status => S, White }
import controllers.routes
import play.api.i18n.Lang

import lila.api.Context
import lila.app.ui.ScalatagsTemplate._
import lila.game.{ Game, Namer, Player, Pov }
import lila.i18n.{ defaultLang, I18nKeys => trans }
import lila.user.Title

trait GameHelper { self: I18nHelper with UserHelper with AiHelper with StringHelper with ChessgroundHelper =>

  def netBaseUrl: String
  def cdnUrl(path: String): String

  def povOpenGraph(pov: Pov) =
    lila.app.ui.OpenGraph(
      image = cdnUrl(routes.Export.gameThumbnail(pov.gameId, None, None).url).some,
      title = titleGame(pov.game),
      url = s"$netBaseUrl${routes.Round.watcher(pov.gameId, pov.color.name).url}",
      description = describePov(pov)
    )

  def titleGame(g: Game) = {
    val speed   = chess.Speed(g.clock.map(_.config)).name
    val variant = g.variant.exotic ?? s" ${g.variant.name}"
    s"$speed$variant Chess • ${playerText(g.whitePlayer)} vs ${playerText(g.blackPlayer)}"
  }

  def describePov(pov: Pov) = {
    import pov._
    val p1 = playerText(game.whitePlayer, withRating = true)
    val p2 = playerText(game.blackPlayer, withRating = true)
    val speedAndClock =
      if (game.imported) "imported"
      else
        game.clock.fold(chess.Speed.Correspondence.name) { c =>
          s"${chess.Speed(c.config).name} (${c.config.show})"
        }
    val mode = game.mode.name
    val variant =
      if (game.variant == chess.variant.FromPosition) "position setup chess"
      else if (game.variant.exotic) game.variant.name
      else "chess"
    import chess.Status._
    val result = (game.winner, game.loser, game.status) match {
      case (Some(w), _, Mate)                               => s"${playerText(w)} won by checkmate"
      case (_, Some(l), Resign | Timeout | Cheat | NoStart) => s"${playerText(l)} resigned"
      case (_, Some(l), Outoftime)                          => s"${playerText(l)} forfeits by time"
      case (Some(w), _, UnknownFinish)                      => s"${playerText(w)} won"
      case (_, _, Draw | Stalemate | UnknownFinish)         => "Game is a draw"
      case (_, _, Aborted)                                  => "Game has been aborted"
      case (_, _, VariantEnd) =>
        game.variant match {
          case chess.variant.KingOfTheHill => "King in the center"
          case chess.variant.ThreeCheck    => "Three checks"
          case chess.variant.Antichess     => "Lose all your pieces to win"
          case chess.variant.Atomic        => "Explode or mate your opponent's king to win"
          case chess.variant.Horde         => "Destroy the horde to win"
          case chess.variant.RacingKings   => "Race to the eighth rank to win"
          case chess.variant.Crazyhouse    => "Drop captured pieces on the board"
          case _                           => "Variant ending"
        }
      case _ => "Game is still being played"
    }
    val moves = s"${(1 + game.chess.turns) / 2} moves"
    s"$p1 plays $p2 in a $mode $speedAndClock game of $variant. $result after $moves. Click to replay, analyse, and discuss the game!"
  }

  def shortClockName(clock: Option[Clock.Config])(implicit lang: Lang): Frag =
    clock.fold[Frag](trans.unlimited())(shortClockName)

  def shortClockName(clock: Clock.Config): Frag = raw(clock.show)

  def shortClockName(game: Game)(implicit lang: Lang): Frag =
    game.correspondenceClock
      .map(c => trans.nbDays(c.daysPerTurn)) orElse
      game.clock.map(_.config).map(shortClockName) getOrElse
      trans.unlimited()

  def modeName(mode: Mode)(implicit lang: Lang): String =
    mode match {
      case Mode.Casual => trans.casual.txt()
      case Mode.Rated  => trans.rated.txt()
    }

  def modeNameNoCtx(mode: Mode): String = modeName(mode)(defaultLang)

  def playerUsername(player: Player, withRating: Boolean = true, withTitle: Boolean = true)(implicit
      lang: Lang
  ): Frag =
    player.aiLevel.fold[Frag](
      player.userId.flatMap(lightUser).fold[Frag](trans.anonymous.txt()) { user =>
        frag(
          titleTag(user.title ifTrue withTitle map Title.apply),
          user.name,
          withRating option frag(
            " ",
            player.rating.fold(frag("?")) { rating =>
              if (player.provisional) abbr(title := trans.perfStat.notEnoughRatedGames.txt())(rating, "?")
              else rating
            }
          )
        )
      }
    ) { level =>
      frag(aiName(level))
    }

  def playerText(player: Player, withRating: Boolean = false) =
    Namer.playerTextBlocking(player, withRating)(lightUser)

  def gameVsText(game: Game, withRatings: Boolean = false): String =
    Namer.gameVsTextBlocking(game, withRatings)(lightUser)

  val berserkIconSpan = iconTag("")

  def playerLink(
      player: Player,
      cssClass: Option[String] = None,
      withOnline: Boolean = true,
      withRating: Boolean = true,
      withDiff: Boolean = true,
      engine: Boolean = false,
      withBerserk: Boolean = false,
      mod: Boolean = false,
      link: Boolean = true
  )(implicit ctx: Context): Frag = {
    val statusIcon = (withBerserk && player.berserk) option berserkIconSpan
    player.userId.flatMap(lightUser) match {
      case None =>
        val klass = cssClass.??(" " + _)
        span(cls := s"user-link$klass")(
          (player.aiLevel, player.name) match {
            case (Some(level), _) => aiNameFrag(level)
            case (_, Some(name))  => name
            case _                => trans.anonymous()
          },
          player.rating.ifTrue(withRating && ctx.pref.showRatings) map { rating => s" ($rating)" },
          statusIcon
        )
      case Some(user) =>
        frag(
          (if (link) a else span)(
            cls  := userClass(user.id, cssClass, withOnline),
            href := s"${routes.User show user.name}${if (mod) "?mod" else ""}"
          )(
            withOnline option frag(lineIcon(user), " "),
            playerUsername(player, withRating && ctx.pref.showRatings),
            (player.ratingDiff.ifTrue(withDiff && ctx.pref.showRatings)) map { d =>
              frag(" ", showRatingDiff(d))
            },
            engine option span(
              cls   := "tos_violation",
              title := trans.thisAccountViolatedTos.txt()
            )
          ),
          statusIcon
        )
    }
  }

  def gameEndStatus(game: Game)(implicit lang: Lang): String =
    game.status match {
      case S.Aborted => trans.gameAborted.txt()
      case S.Mate    => trans.checkmate.txt()
      case S.Resign =>
        (if (game.loser.exists(_.color.white)) trans.whiteResigned else trans.blackResigned).txt()
      case S.UnknownFinish => trans.finished.txt()
      case S.Stalemate     => trans.stalemate.txt()
      case S.Timeout =>
        (game.loser, game.turnColor) match {
          case (Some(p), _) if p.color.white => trans.whiteLeftTheGame.txt()
          case (Some(_), _)                  => trans.blackLeftTheGame.txt()
          case (None, White)                 => trans.whiteLeftTheGame.txt() + " • " + trans.draw.txt()
          case (None, Black)                 => trans.blackLeftTheGame.txt() + " • " + trans.draw.txt()
        }
      case S.Draw => {
        import lila.game.DrawReason._
        game.drawReason match {
          case Some(MutualAgreement)      => trans.drawByMutualAgreement.txt()
          case Some(FiftyMoves)           => trans.fiftyMovesWithoutProgress.txt() + " • " + trans.draw.txt()
          case Some(ThreefoldRepetition)  => trans.threefoldRepetition.txt() + " • " + trans.draw.txt()
          case Some(InsufficientMaterial) => trans.insufficientMaterial.txt() + " • " + trans.draw.txt()
          case _                          => trans.draw.txt()
        }
      }
      case S.Outoftime =>
        (game.turnColor, game.loser) match {
          case (White, Some(_)) => trans.whiteTimeOut.txt()
          case (White, None)    => trans.whiteTimeOut.txt() + " • " + trans.draw.txt()
          case (Black, Some(_)) => trans.blackTimeOut.txt()
          case (Black, None)    => trans.blackTimeOut.txt() + " • " + trans.draw.txt()
        }
      case S.NoStart =>
        (if (game.loser.exists(_.color.white)) trans.whiteDidntMove else trans.blackDidntMove).txt()
      case S.Cheat => trans.cheatDetected.txt()
      case S.VariantEnd =>
        game.variant match {
          case chess.variant.KingOfTheHill => trans.kingInTheCenter.txt()
          case chess.variant.ThreeCheck    => trans.threeChecks.txt()
          case chess.variant.RacingKings   => trans.raceFinished.txt()
          case _                           => trans.variantEnding.txt()
        }
      case _ => ""
    }

  def gameTitle(game: Game, color: Color): String = {
    val u1 = playerText(game player color, withRating = true)
    val u2 = playerText(game opponent color, withRating = true)
    val clock = game.clock ?? { c =>
      " • " + c.config.show
    }
    val variant = game.variant.exotic ?? s" • ${game.variant.name}"
    s"$u1 vs $u2$clock$variant"
  }

  // whiteUsername 1-0 blackUsername
  def gameSummary(whiteUserId: String, blackUserId: String, finished: Boolean, result: Option[Boolean]) = {
    val res = Outcome.showResult(finished option Outcome(result map Color.fromWhite))
    s"${titleNameOrId(whiteUserId)} $res ${titleNameOrId(blackUserId)}"
  }

  def gameResult(game: Game) =
    Outcome.showResult(game.finished option Outcome(game.winnerColor))

  def gameLink(
      game: Game,
      color: Color,
      ownerLink: Boolean = false,
      tv: Boolean = false
  )(implicit ctx: Context): String = {
    val owner = ownerLink ?? ctx.me.flatMap(game.player)
    if (tv) routes.Tv.index
    else
      owner.fold(routes.Round.watcher(game.id, color.name)) { o =>
        routes.Round.player(game fullIdOf o.color)
      }
  }.toString

  def gameLink(pov: Pov)(implicit ctx: Context): String = gameLink(pov.game, pov.color)

  def challengeTitle(c: lila.challenge.Challenge)(implicit ctx: Context) = {
    val speed = c.clock.map(_.config).fold(chess.Speed.Correspondence.name) { clock =>
      s"${chess.Speed(clock).name} (${clock.show})"
    }
    val variant = c.variant.exotic ?? s" ${c.variant.name}"
    val challenger = c.challengerUser.fold(trans.anonymous.txt()) { reg =>
      s"${titleNameOrId(reg.id)}${ctx.pref.showRatings ?? s" (${reg.rating.show})"}"
    }
    val players =
      if (c.isOpen) "Open challenge"
      else
        c.destUser.fold(s"Challenge from $challenger") { dest =>
          s"$challenger challenges ${titleNameOrId(dest.id)}${ctx.pref.showRatings ?? s" (${dest.rating.show})"}"
        }
    s"$speed$variant ${c.mode.name} Chess • $players"
  }

  def challengeOpenGraph(c: lila.challenge.Challenge)(implicit ctx: Context) =
    lila.app.ui.OpenGraph(
      title = challengeTitle(c),
      url = s"$netBaseUrl${routes.Round.watcher(c.id, chess.White.name).url}",
      description = "Join the challenge or watch the game here."
    )
}
