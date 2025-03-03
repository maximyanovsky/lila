package lila.tournament

import BSONHandlers._
import reactivemongo.akkastream.{ cursorProducer, AkkaStreamCursor }
import reactivemongo.api._
import reactivemongo.api.bson._

import lila.db.dsl._
import lila.hub.LightTeam.TeamID
import lila.rating.PerfType
import lila.user.User

final class PlayerRepo(coll: Coll)(implicit ec: scala.concurrent.ExecutionContext) {

  private def selectTour(tourId: Tournament.ID) = $doc("tid" -> tourId)
  private def selectTourUser(tourId: Tournament.ID, userId: User.ID) =
    $doc(
      "tid" -> tourId,
      "uid" -> userId
    )
  private val selectActive   = $doc("w" $ne true)
  private val selectWithdraw = $doc("w" -> true)
  private val bestSort       = $doc("m" -> -1)

  def byId(id: Tournament.ID): Fu[Option[Player]] = coll.one[Player]($id(id))

  private[tournament] def byPlayerIdsOnPage(
      tourId: Tournament.ID,
      playerIds: List[Player.ID],
      page: Int
  ): Fu[RankedPlayers] =
    coll.find($inIds(playerIds)).cursor[Player]().list() map { players =>
      playerIds.flatMap(id => players.find(_._id == id)).zipWithIndex.map { case (player, index) =>
        RankedPlayer((page - 1) * 10 + index + 1, player)
      }
    }

  private[tournament] def bestByTour(tourId: Tournament.ID, nb: Int, skip: Int = 0): Fu[List[Player]] =
    coll.find(selectTour(tourId)).sort(bestSort).skip(skip).cursor[Player]().list(nb)

  private[tournament] def bestByTourWithRank(
      tourId: Tournament.ID,
      nb: Int,
      skip: Int = 0
  ): Fu[RankedPlayers] =
    bestByTour(tourId, nb, skip).map { res =>
      res
        .foldRight(List.empty[RankedPlayer] -> (res.size + skip)) { case (p, (res, rank)) =>
          (RankedPlayer(rank, p) :: res, rank - 1)
        }
        ._1
    }

  private[tournament] def bestByTourWithRankByPage(
      tourId: Tournament.ID,
      nb: Int,
      page: Int
  ): Fu[RankedPlayers] =
    bestByTourWithRank(tourId, nb, (page - 1) * nb)

  // very expensive
  private[tournament] def bestTeamIdsByTour(
      tourId: Tournament.ID,
      battle: TeamBattle
  ): Fu[List[TeamBattle.RankedTeam]] = {
    import TeamBattle.{ RankedTeam, TeamLeader }
    coll
      .aggregateList(maxDocs = TeamBattle.maxTeams) { framework =>
        import framework._
        Match(selectTour(tourId)) -> List(
          Sort(Descending("m")),
          GroupField("t")(
            "m" -> Push(
              $doc(
                "u" -> "$uid",
                "m" -> "$m"
              )
            )
          ),
          Limit(TeamBattle.maxTeams),
          Project(
            $doc(
              "p" -> $doc(
                "$slice" -> $arr("$m", battle.nbLeaders)
              )
            )
          )
        )
      }
      .map {
        _.flatMap { doc =>
          for {
            teamId      <- doc.getAsOpt[TeamID]("_id")
            leadersBson <- doc.getAsOpt[List[Bdoc]]("p")
            leaders = leadersBson.flatMap { p: Bdoc =>
              for {
                id    <- p.getAsOpt[User.ID]("u")
                magic <- p.int("m")
              } yield TeamLeader(id, magic)
            }
          } yield new RankedTeam(0, teamId, leaders)
        }.sorted.zipWithIndex map { case (rt, pos) =>
          rt.updateRank(pos + 1)
        }
      } map { ranked =>
      if (ranked.sizeIs == battle.teams.size) ranked
      else
        ranked ::: battle.teams
          .foldLeft(List.empty[RankedTeam]) {
            case (missing, team) if !ranked.exists(_.teamId == team) =>
              new RankedTeam(missing.headOption.fold(ranked.size)(_.rank) + 1, team, Nil, 0) :: missing
            case (acc, _) => acc
          }
          .reverse
    }
  }

  // very expensive
  private[tournament] def teamInfo(
      tourId: Tournament.ID,
      teamId: TeamID
  ): Fu[TeamBattle.TeamInfo] = {
    coll
      .aggregateOne() { framework =>
        import framework._
        Match(selectTour(tourId) ++ $doc("t" -> teamId)) -> List(
          Sort(Descending("m")),
          Facet(
            List(
              "agg" -> List(
                Group(BSONNull)(
                  "nb"     -> SumAll,
                  "rating" -> AvgField("r"),
                  "perf"   -> Avg($doc("$cond" -> $arr("$e", "$e", "$r"))),
                  "score"  -> AvgField("s")
                )
              ),
              "topPlayers" -> List(Limit(50))
            )
          )
        )
      }
      .map { docO =>
        for {
          doc       <- docO
          aggs      <- doc.getAsOpt[List[Bdoc]]("agg")
          agg       <- aggs.headOption
          nbPlayers <- agg.int("nb")
          rating = agg.double("rating").??(math.round)
          perf   = agg.double("perf").??(math.round)
          score  = agg.double("score").??(math.round)
          topPlayers <- doc.getAsOpt[List[Player]]("topPlayers")
        } yield TeamBattle.TeamInfo(teamId, nbPlayers, rating.toInt, perf.toInt, score.toInt, topPlayers)
      }
      .dmap(_ | TeamBattle.TeamInfo(teamId, 0, 0, 0, 0, Nil))
  }

  def bestTeamPlayers(tourId: Tournament.ID, teamId: TeamID, nb: Int): Fu[List[Player]] =
    coll.find($doc("tid" -> tourId, "t" -> teamId)).sort($sort desc "m").cursor[Player]().list(nb)

  def countTeamPlayers(tourId: Tournament.ID, teamId: TeamID): Fu[Int] =
    coll.countSel($doc("tid" -> tourId, "t" -> teamId))

  def teamsOfPlayers(tourId: Tournament.ID, userIds: Seq[User.ID]): Fu[List[(User.ID, TeamID)]] =
    coll
      .find($doc("tid" -> tourId, "uid" $in userIds), $doc("_id" -> false, "uid" -> true, "t" -> true).some)
      .cursor[Bdoc]()
      .list()
      .map {
        _.flatMap { doc =>
          doc.getAsOpt[User.ID]("uid") flatMap { userId =>
            doc.getAsOpt[TeamID]("t") map { (userId, _) }
          }
        }
      }

  def teamVs(tourId: Tournament.ID, game: lila.game.Game): Fu[Option[TeamBattle.TeamVs]] =
    game.twoUserIds ?? { case (w, b) =>
      teamsOfPlayers(tourId, List(w, b)).dmap(_.toMap) map { m =>
        import cats.implicits._
        (m.get(w), m.get(b)).mapN((_, _)) ?? { case (wt, bt) =>
          TeamBattle.TeamVs(chess.Color.Map(wt, bt)).some
        }
      }
    }

  def count(tourId: Tournament.ID): Fu[Int] = coll.countSel(selectTour(tourId))

  def removeByTour(tourId: Tournament.ID) = coll.delete.one(selectTour(tourId)).void

  def remove(tourId: Tournament.ID, userId: User.ID) =
    coll.delete.one(selectTourUser(tourId, userId)).void

  def existsActive(tourId: Tournament.ID, userId: User.ID) =
    coll.exists(selectTourUser(tourId, userId) ++ selectActive)

  def exists(tourId: Tournament.ID, userId: User.ID) =
    coll.exists(selectTourUser(tourId, userId))

  def unWithdraw(tourId: Tournament.ID) =
    coll.update
      .one(
        selectTour(tourId) ++ selectWithdraw,
        $doc("$unset" -> $doc("w" -> true)),
        multi = true
      )
      .void

  def find(tourId: Tournament.ID, userId: User.ID): Fu[Option[Player]] =
    coll.find(selectTourUser(tourId, userId)).one[Player]

  def update(tourId: Tournament.ID, userId: User.ID)(f: Player => Fu[Player]): Funit =
    find(tourId, userId) orFail s"No such player: $tourId/$userId" flatMap f flatMap update

  def update(player: Player): Funit = coll.update.one($id(player._id), player).void

  def join(
      tourId: Tournament.ID,
      user: User,
      perfType: PerfType,
      team: Option[TeamID],
      prev: Option[Player]
  ) =
    prev match {
      case Some(p) if p.withdraw => coll.update.one($id(p._id), $unset("w"))
      case Some(_)               => funit
      case None                  => coll.insert.one(Player.make(tourId, user, perfType, team))
    }

  def withdraw(tourId: Tournament.ID, userId: User.ID) =
    coll.update.one(selectTourUser(tourId, userId), $set("w" -> true)).void

  private[tournament] def withPoints(tourId: Tournament.ID): Fu[List[Player]] =
    coll.list[Player](
      selectTour(tourId) ++ $doc("m" $gt 0)
    )

  private[tournament] def nbActivePlayers(tourId: Tournament.ID): Fu[Int] =
    coll.countSel(selectTour(tourId) ++ selectActive)

  def winner(tourId: Tournament.ID): Fu[Option[Player]] =
    coll.find(selectTour(tourId)).sort(bestSort).one[Player]

  // freaking expensive (marathons)
  // note: tournaments before ISODate("2015-06-15T03:34:01.134Z") (s0tKhoTU)
  // have player IDs with a length <= 8, breaking this optimization
  // instead of fixing it with `$doc("$concat" -> $arr("$_id", ":", "$uid"))`
  // we can just hide the damage in the UI
  // to save serverside perfs
  private[tournament] def computeRanking(tourId: Tournament.ID): Fu[FullRanking] =
    coll
      .aggregateWith[Bdoc]() { framework =>
        import framework._
        List(
          Match(selectTour(tourId)),
          Sort(Descending("m")),
          Group(BSONNull)(
            "all" -> Push(
              $doc("$concat" -> $arr("$_id", "$uid"))
            )
          )
        )
      }
      .headOption
      .map {
        _.flatMap(_.getAsOpt[BSONArray]("all"))
          .fold(FullRanking(Map.empty, Array.empty)) { all =>
            // mutable optimized implementation
            val playerIndex = new Array[Player.ID](all.size)
            val ranking     = Map.newBuilder[User.ID, Int]
            var r           = 0
            for (u <- all.values) {
              val both   = u.asInstanceOf[BSONString].value
              val userId = both.drop(8)
              playerIndex(r) = both.take(8)
              ranking += (userId -> r)
              r = r + 1
            }
            FullRanking(ranking.result(), playerIndex)
          }
      }

  def computeRankOf(player: Player): Fu[Int] =
    coll.countSel(selectTour(player.tourId) ++ $doc("m" $gt player.magicScore))

  // expensive, cache it
  private[tournament] def averageRating(tourId: Tournament.ID): Fu[Int] =
    coll
      .aggregateWith[Bdoc]() { framework =>
        import framework._
        List(Match(selectTour(tourId)), Group(BSONNull)("rating" -> AvgField("r")))
      }
      .headOption map {
      ~_.flatMap(_.double("rating").map(_.toInt))
    }

  def byTourAndUserIds(tourId: Tournament.ID, userIds: Iterable[User.ID]): Fu[List[Player]] =
    coll
      .list[Player](selectTour(tourId) ++ $doc("uid" $in userIds))
      .chronometer
      .logIfSlow(200, logger) { players =>
        s"PlayerRepo.byTourAndUserIds $tourId ${userIds.size} user IDs, ${players.size} players"
      }
      .result

  def pairByTourAndUserIds(tourId: Tournament.ID, id1: User.ID, id2: User.ID): Fu[Option[(Player, Player)]] =
    byTourAndUserIds(tourId, List(id1, id2)) map {
      case List(p1, p2) if p1.is(id1) && p2.is(id2) => Some(p1 -> p2)
      case List(p1, p2) if p1.is(id2) && p2.is(id1) => Some(p2 -> p1)
      case _                                        => none
    }

  def setPerformance(player: Player, performance: Int) =
    coll.updateField($id(player.id), "e", performance).void

  private def rankPlayers(players: List[Player], ranking: Ranking): RankedPlayers =
    players
      .flatMap { p =>
        ranking get p.userId map { RankedPlayer(_, p) }
      }
      .sortBy(_.rank)

  def rankedByTourAndUserIds(
      tourId: Tournament.ID,
      userIds: Iterable[User.ID],
      ranking: Ranking
  ): Fu[RankedPlayers] =
    byTourAndUserIds(tourId, userIds)
      .map { rankPlayers(_, ranking) }
      .chronometer
      .logIfSlow(200, logger) { players =>
        s"PlayerRepo.rankedByTourAndUserIds $tourId ${userIds.size} user IDs, ${ranking.size} ranking, ${players.size} players"
      }
      .result

  def searchPlayers(tourId: Tournament.ID, term: String, nb: Int): Fu[List[User.ID]] =
    User.validateId(term) ?? { valid =>
      coll.primitive[User.ID](
        selector = $doc(
          "tid" -> tourId,
          "uid" $startsWith valid
        ),
        sort = $sort desc "m",
        nb = nb,
        field = "uid"
      )
    }

  private[tournament] def sortedCursor(
      tournamentId: Tournament.ID,
      batchSize: Int,
      readPreference: ReadPreference = ReadPreference.secondaryPreferred
  ): AkkaStreamCursor[Player] =
    coll
      .find(selectTour(tournamentId))
      .sort($sort desc "m")
      .batchSize(batchSize)
      .cursor[Player](readPreference)

}
