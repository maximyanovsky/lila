package lila.tournament

import chess.Clock.{ Config => TournamentClock }
import org.joda.time.DateTime
import scala.concurrent.duration._
import scala.concurrent.Promise

import lila.memo.ExpireSetMemo
import lila.user.User

private case class WaitingUsers(
    hash: Map[User.ID, DateTime],
    apiUsers: Option[ExpireSetMemo],
    clock: TournamentClock,
    date: DateTime
) {

  // ultrabullet -> 8
  // hyperbullet -> 10
  // 1+0  -> 12  -> 15
  // 3+0  -> 24  -> 24
  // 5+0  -> 36  -> 36
  // 10+0 -> 66  -> 50
  private val waitSeconds: Int =
    if (clock.estimateTotalSeconds < 30) 8
    else if (clock.estimateTotalSeconds < 60) 10
    else (clock.estimateTotalSeconds / 10 + 6) atMost 50 atLeast 15

  lazy val all  = hash.keySet
  lazy val size = hash.size

  def isOdd = size % 2 == 1

  // skips the most recent user if odd
  def evenNumber: Set[User.ID] = {
    if (isOdd) all - hash.maxBy(_._2.getMillis)._1
    else all
  }

  lazy val haveWaitedEnough: Boolean =
    size > 100 || {
      val since                      = date minusSeconds waitSeconds
      val nbConnectedLongEnoughUsers = hash.count { case (_, d) => d.isBefore(since) }
      nbConnectedLongEnoughUsers > 1
    }

  def update(fromWebSocket: Set[User.ID]) = {
    val newDate      = DateTime.now
    val withApiUsers = fromWebSocket ++ apiUsers.??(_.keySet)
    copy(
      date = newDate,
      hash = {
        hash.view.filterKeys(withApiUsers.contains) ++
          withApiUsers.filterNot(hash.contains).map { _ -> newDate }
      }.toMap
    )
  }

  def hasUser(userId: User.ID) = hash contains userId

  def addApiUser(userId: User.ID) = {
    val memo = apiUsers | new ExpireSetMemo(70 seconds)
    memo put userId
    if (apiUsers.isEmpty) copy(apiUsers = memo.some) else this
  }

  def removePairedUsers(us: Set[User.ID]) = {
    apiUsers.foreach(_ removeAll us)
    copy(hash = hash -- us)
  }
}

final private class WaitingUsersApi {

  private val store = new java.util.concurrent.ConcurrentHashMap[Tournament.ID, WaitingUsers.WithNext](64)

  def hasUser(tourId: Tournament.ID, userId: User.ID): Boolean =
    Option(store get tourId).exists(_.waiting hasUser userId)

  def registerNextPromise(tour: Tournament, promise: Promise[WaitingUsers]) =
    updateOrCreate(tour)(_.copy(next = promise.some))

  def registerWaitingUsers(tourId: Tournament.ID, users: Set[User.ID]) =
    store.computeIfPresent(
      tourId,
      (_: Tournament.ID, cur: WaitingUsers.WithNext) => {
        val newWaiting = cur.waiting.update(users)
        cur.next.foreach(_ success newWaiting)
        WaitingUsers.WithNext(newWaiting, none)
      }
    )

  def registerPairedUsers(tourId: Tournament.ID, users: Set[User.ID]) =
    store.computeIfPresent(
      tourId,
      (_: Tournament.ID, cur: WaitingUsers.WithNext) =>
        cur.copy(waiting = cur.waiting removePairedUsers users)
    )

  def addApiUser(tour: Tournament, user: User) = updateOrCreate(tour) { w =>
    w.copy(waiting = w.waiting addApiUser user.id)
  }

  def remove(id: Tournament.ID) = store remove id

  private def updateOrCreate(tour: Tournament)(f: WaitingUsers.WithNext => WaitingUsers.WithNext) =
    store.compute(
      tour.id,
      (_: Tournament.ID, cur: WaitingUsers.WithNext) =>
        f(
          Option(cur) | WaitingUsers.WithNext(
            WaitingUsers(Map.empty, None, tour.clock, DateTime.now),
            none
          )
        )
    )
}

private object WaitingUsers {
  case class WithNext(waiting: WaitingUsers, next: Option[Promise[WaitingUsers]])
}
