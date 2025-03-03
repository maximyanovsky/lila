package lila.tournament

import chess.Color
import chess.variant._
import lila.game.Game
import lila.user.User
import lila.common.ThreadLocalRandom

case class Pairing(
    id: Game.ID,
    tourId: Tournament.ID,
    status: chess.Status,
    user1: User.ID,
    user2: User.ID,
    winner: Option[User.ID],
    turns: Option[Int],
    berserk1: Boolean,
    berserk2: Boolean
) {

  def gameId = id

  def users                                       = List(user1, user2)
  def usersPair                                   = user1 -> user2
  def contains(user: User.ID): Boolean            = user1 == user || user2 == user
  def contains(u1: User.ID, u2: User.ID): Boolean = contains(u1) && contains(u2)
  def notContains(user: User.ID)                  = !contains(user)

  def opponentOf(userId: User.ID) =
    if (userId == user1) user2.some
    else if (userId == user2) user1.some
    else none

  def finished = status >= chess.Status.Mate
  def playing  = !finished

  def quickFinish      = finished && turns.exists(20 >)
  def quickDraw        = draw && turns.exists(20 >)
  def notSoQuickFinish = finished && turns.exists(14 <=)
  def longGame(variant: Variant) = turns.exists(_ >= (variant match {
    case Standard | Chess960 | Horde => 60
    case Crazyhouse | KingOfTheHill  => 50
    case Antichess | RacingKings     => 40
    case ThreeCheck | Atomic         => 20
  }))

  def wonBy(user: User.ID): Boolean     = winner.has(user)
  def lostBy(user: User.ID): Boolean    = winner.exists(user !=)
  def notLostBy(user: User.ID): Boolean = winner.fold(true)(user ==)
  def draw: Boolean                     = finished && winner.isEmpty

  def colorOf(userId: User.ID): Option[Color] =
    if (userId == user1) Color.White.some
    else if (userId == user2) Color.Black.some
    else none

  def berserkOf(userId: User.ID): Boolean =
    if (userId == user1) berserk1
    else if (userId == user2) berserk2
    else false

  def berserkOf(color: Color) = color.fold(berserk1, berserk2)

  def similar(other: Pairing) = other.contains(user1, user2)
}

private[tournament] object Pairing {

  case class LastOpponents(hash: Map[User.ID, User.ID]) extends AnyVal

  case class WithPlayers(pairing: Pairing, player1: Player, player2: Player)

  private def make(
      gameId: Game.ID,
      tourId: Tournament.ID,
      u1: User.ID,
      u2: User.ID
  ) =
    new Pairing(
      id = gameId,
      tourId = tourId,
      status = chess.Status.Created,
      user1 = u1,
      user2 = u2,
      winner = none,
      turns = none,
      berserk1 = false,
      berserk2 = false
    )

  case class Prep(player1: Player, player2: Player) {
    def toPairing(tourId: Tournament.ID, gameId: Game.ID): Pairing.WithPlayers =
      WithPlayers(make(gameId, tourId, player1.userId, player2.userId), player1, player2)
  }

  def prepWithColor(p1: RankedPlayerWithColorHistory, p2: RankedPlayerWithColorHistory) =
    if (p1.colorHistory.firstGetsWhite(p2.colorHistory)(() => ThreadLocalRandom.nextBoolean()))
      Prep(p1.player, p2.player)
    else Prep(p2.player, p1.player)

  def prepWithRandomColor(p1: Player, p2: Player) =
    if (ThreadLocalRandom.nextBoolean()) Prep(p1, p2) else Prep(p2, p1)
}
