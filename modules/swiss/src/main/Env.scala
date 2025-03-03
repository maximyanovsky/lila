package lila.swiss

import com.softwaremill.macwire._
import play.api.Configuration
import scala.concurrent.duration._

import lila.common.config._
import lila.common.LilaScheduler
import lila.socket.Socket.{ GetVersion, SocketVersion }

@Module
final class Env(
    appConfig: Configuration,
    db: lila.db.Db,
    gameRepo: lila.game.GameRepo,
    userRepo: lila.user.UserRepo,
    onStart: lila.round.OnStart,
    remoteSocketApi: lila.socket.RemoteSocket,
    chatApi: lila.chat.ChatApi,
    cacheApi: lila.memo.CacheApi,
    lightUserApi: lila.user.LightUserApi,
    historyApi: lila.history.HistoryApi,
    gameProxyRepo: lila.round.GameProxyRepo,
    roundSocket: lila.round.RoundSocket,
    mongoCache: lila.memo.MongoCache.Api,
    baseUrl: lila.common.config.BaseUrl
)(implicit
    ec: scala.concurrent.ExecutionContext,
    system: akka.actor.ActorSystem,
    scheduler: akka.actor.Scheduler,
    mat: akka.stream.Materializer,
    idGenerator: lila.game.IdGenerator,
    mode: play.api.Mode
) {

  private val colls = wire[SwissColls]

  private val sheetApi = wire[SwissSheetApi]

  private lazy val rankingApi: SwissRankingApi = wire[SwissRankingApi]

  val trf: SwissTrf = wire[SwissTrf]

  private val pairingSystem = new PairingSystem(trf, rankingApi, appConfig.get[String]("swiss.bbpairing"))

  private val manualPairing = wire[SwissManualPairing]

  private val scoring = wire[SwissScoring]

  private val director = wire[SwissDirector]

  private val boardApi = wire[SwissBoardApi]

  private val statsApi = wire[SwissStatsApi]

  lazy val verify = wire[SwissCondition.Verify]

  val api: SwissApi = wire[SwissApi]

  lazy val roundPager = wire[SwissRoundPager]

  private def teamOf = api.teamOf _

  private lazy val socket = wire[SwissSocket]

  def version(swissId: Swiss.Id): Fu[SocketVersion] =
    socket.rooms.ask[SocketVersion](swissId.value)(GetVersion)

  lazy val standingApi = wire[SwissStandingApi]

  lazy val json = wire[SwissJson]

  lazy val forms = wire[SwissForm]

  lazy val feature = wire[SwissFeature]

  lazy val cache: SwissCache = wire[SwissCache]

  lazy val getName = new GetSwissName(cache.name)

  private lazy val officialSchedule = wire[SwissOfficialSchedule]

  wire[SwissNotify]

  lila.common.Bus.subscribeFun(
    "finishGame",
    "adjustCheater",
    "adjustBooster",
    "teamLeave"
  ) {
    case lila.game.actorApi.FinishGame(game, _, _)        => api.finishGame(game).unit
    case lila.hub.actorApi.team.LeaveTeam(teamId, userId) => api.leaveTeam(teamId, userId).unit
    case lila.hub.actorApi.mod.MarkCheater(userId, true)  => api.kickLame(userId).unit
    case lila.hub.actorApi.mod.MarkBooster(userId)        => api.kickLame(userId).unit
  }

  LilaScheduler(_.Every(1 seconds), _.AtMost(20 seconds), _.Delay(20 seconds))(api.startPendingRounds)

  LilaScheduler(_.Every(10 seconds), _.AtMost(15 seconds), _.Delay(20 seconds))(api.checkOngoingGames)

  LilaScheduler(_.Every(1 hour), _.AtMost(15 seconds), _.Delay(5 minutes))(officialSchedule.generate)
}

private class SwissColls(db: lila.db.Db) {
  val swiss   = db(CollName("swiss"))
  val player  = db(CollName("swiss_player"))
  val pairing = db(CollName("swiss_pairing"))
}
