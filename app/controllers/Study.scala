package controllers

import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.duration._
import scala.util.chaining._

import lila.api.Context
import lila.app._
import lila.chat.Chat
import lila.common.paginator.{ Paginator, PaginatorJson }
import lila.common.{ HTTPRequest, IpAddress }
import lila.study.actorApi.Who
import lila.study.JsonView.JsData
import lila.study.Study.WithChapter
import lila.study.{ Chapter, Order, Study => StudyModel }
import lila.tree.Node.partitionTreeJsonWriter
import views._

final class Study(
    env: Env,
    editorC: => Editor,
    userAnalysisC: => UserAnalysis,
    apiC: => Api,
    prismicC: Prismic
) extends LilaController(env) {

  def search(text: String, page: Int) =
    OpenBody { implicit ctx =>
      Reasonable(page) {
        if (text.trim.isEmpty)
          env.study.pager.all(ctx.me, Order.default, page) flatMap { pag =>
            preloadMembers(pag) >> negotiate(
              html = Ok(html.study.list.all(pag, Order.default)).fuccess,
              api = _ => apiStudies(pag)
            )
          }
        else
          env.studySearch(ctx.me)(text, page) flatMap { pag =>
            negotiate(
              html = Ok(html.study.list.search(pag, text)).fuccess,
              api = _ => apiStudies(pag)
            )
          }
      }
    }

  def homeLang =
    LangPage(routes.Study.allDefault())(allResults(Order.Hot.key, 1)(_)) _

  def allDefault(page: Int) = all(Order.Hot.key, page)

  def all(o: String, page: Int) =
    Open { implicit ctx =>
      allResults(o, page)
    }

  private def allResults(o: String, page: Int)(implicit ctx: Context) =
    Reasonable(page) {
      Order(o) match {
        case order if !Order.withoutSelector.contains(order) =>
          Redirect(routes.Study.allDefault(page)).fuccess
        case order =>
          env.study.pager.all(ctx.me, order, page) flatMap { pag =>
            preloadMembers(pag) >> negotiate(
              html = Ok(html.study.list.all(pag, order)).fuccess,
              api = _ => apiStudies(pag)
            )
          }
      }
    }

  def byOwnerDefault(username: String, page: Int) = byOwner(username, Order.default.key, page)

  def byOwner(username: String, order: String, page: Int) =
    Open { implicit ctx =>
      env.user.repo.named(username).flatMap {
        _.fold(notFound(ctx)) { owner =>
          env.study.pager.byOwner(owner, ctx.me, Order(order), page) flatMap { pag =>
            preloadMembers(pag) >> negotiate(
              html = Ok(html.study.list.byOwner(pag, Order(order), owner)).fuccess,
              api = _ => apiStudies(pag)
            )
          }
        }
      }
    }

  def mine(order: String, page: Int) =
    Auth { implicit ctx => me =>
      env.study.pager.mine(me, Order(order), page) flatMap { pag =>
        preloadMembers(pag) >> negotiate(
          html = env.study.topicApi.userTopics(me.id) map { topics =>
            Ok(html.study.list.mine(pag, Order(order), me, topics))
          },
          api = _ => apiStudies(pag)
        )
      }
    }

  def minePublic(order: String, page: Int) =
    Auth { implicit ctx => me =>
      env.study.pager.minePublic(me, Order(order), page) flatMap { pag =>
        preloadMembers(pag) >> negotiate(
          html = Ok(html.study.list.minePublic(pag, Order(order), me)).fuccess,
          api = _ => apiStudies(pag)
        )
      }
    }

  def minePrivate(order: String, page: Int) =
    Auth { implicit ctx => me =>
      env.study.pager.minePrivate(me, Order(order), page) flatMap { pag =>
        preloadMembers(pag) >> negotiate(
          html = Ok(html.study.list.minePrivate(pag, Order(order), me)).fuccess,
          api = _ => apiStudies(pag)
        )
      }
    }

  def mineMember(order: String, page: Int) =
    Auth { implicit ctx => me =>
      env.study.pager.mineMember(me, Order(order), page) flatMap { pag =>
        preloadMembers(pag) >> negotiate(
          html = env.study.topicApi.userTopics(me.id) map { topics =>
            Ok(html.study.list.mineMember(pag, Order(order), me, topics))
          },
          api = _ => apiStudies(pag)
        )
      }
    }

  def mineLikes(order: String, page: Int) =
    Auth { implicit ctx => me =>
      env.study.pager.mineLikes(me, Order(order), page) flatMap { pag =>
        preloadMembers(pag) >> negotiate(
          html = Ok(html.study.list.mineLikes(pag, Order(order))).fuccess,
          api = _ => apiStudies(pag)
        )
      }
    }

  def byTopic(name: String, order: String, page: Int) =
    Open { implicit ctx =>
      lila.study.StudyTopic fromStr name match {
        case None => notFound
        case Some(topic) =>
          env.study.pager.byTopic(topic, ctx.me, Order(order), page) zip
            ctx.me.??(u => env.study.topicApi.userTopics(u.id) dmap some) flatMap { case (pag, topics) =>
              preloadMembers(pag) inject Ok(html.study.topic.show(topic, pag, Order(order), topics))
            }
      }
    }

  private def preloadMembers(pag: Paginator[StudyModel.WithChaptersAndLiked]) =
    env.user.lightUserApi.preloadMany(
      pag.currentPageResults.view
        .flatMap(_.study.members.members.values take StudyModel.previewNbMembers)
        .map(_.id)
        .toSeq
    )

  private def apiStudies(pager: Paginator[StudyModel.WithChaptersAndLiked]) = {
    implicit val pagerWriter = Writes[StudyModel.WithChaptersAndLiked] { s =>
      env.study.jsonView.pagerData(s)
    }
    Ok(Json.obj("paginator" -> PaginatorJson(pager))).fuccess
  }

  private def orRelay(id: String, chapterId: Option[String] = None)(
      f: => Fu[Result]
  )(implicit ctx: Context): Fu[Result] =
    if (HTTPRequest isRedirectable ctx.req) env.relay.api.getOngoing(lila.relay.RelayRound.Id(id)) flatMap {
      _.fold(f) { rt =>
        Redirect(chapterId.map(Chapter.Id).fold(rt.path)(rt.path)).fuccess
      }
    }
    else f

  private def showQuery(query: Fu[Option[WithChapter]])(implicit ctx: Context): Fu[Result] =
    OptionFuResult(query) { oldSc =>
      CanView(oldSc.study, ctx.me) {
        for {
          (sc, data) <- getJsonData(oldSc)
          res <- negotiate(
            html = for {
              chat      <- chatOf(sc.study)
              sVersion  <- env.study.version(sc.study.id)
              streamers <- streamersOf(sc.study)
            } yield EnableSharedArrayBuffer(Ok(html.study.show(sc.study, data, chat, sVersion, streamers))),
            api = _ =>
              chatOf(sc.study).map { chatOpt =>
                Ok(
                  Json.obj(
                    "study" -> data.study.add("chat" -> chatOpt.map { c =>
                      lila.chat.JsonView.mobile(
                        chat = c.chat,
                        writeable = ctx.userId.??(sc.study.canChat)
                      )
                    }),
                    "analysis" -> data.analysis
                  )
                )
              }
          )
        } yield res
      }(privateUnauthorizedFu(oldSc.study), privateForbiddenFu(oldSc.study))
    } map NoCache

  private[controllers] def getJsonData(sc: WithChapter)(implicit ctx: Context): Fu[(WithChapter, JsData)] =
    for {
      chapters                <- env.study.chapterRepo.orderedMetadataByStudy(sc.study.id)
      (study, resetToChapter) <- env.study.api.resetIfOld(sc.study, chapters)
      chapter = resetToChapter | sc.chapter
      _ <- env.user.lightUserApi preloadMany study.members.ids.toList
      pov = userAnalysisC.makePov(chapter.root.fen.some, chapter.setup.variant)
      analysis <- chapter.serverEval.exists(_.done) ?? env.analyse.analyser.byId(chapter.id.value)
      division = analysis.isDefined option env.study.serverEvalMerger.divisionOf(chapter)
      baseData <- env.api.roundApi.withExternalEngines(
        ctx.me,
        env.round.jsonView.userAnalysisJson(
          pov,
          ctx.pref,
          chapter.root.fen.some,
          chapter.setup.orientation,
          owner = false,
          me = ctx.me,
          division = division
        )
      )
      studyJson <- env.study.jsonView(study, chapters, chapter, ctx.me)
    } yield WithChapter(study, chapter) -> JsData(
      study = studyJson,
      analysis = baseData
        .add(
          "treeParts" -> partitionTreeJsonWriter.writes {
            lila.study.TreeBuilder(chapter.root, chapter.setup.variant)
          }.some
        )
        .add("analysis" -> analysis.map { lila.study.ServerEval.toJson(chapter, _) })
    )

  def show(id: String) =
    Open { implicit ctx =>
      orRelay(id) {
        showQuery(env.study.api byIdWithChapter id)
      }
    }

  def chapter(id: String, chapterId: String) =
    Open { implicit ctx =>
      orRelay(id, chapterId.some) {
        showQuery(env.study.api.byIdWithChapter(id, chapterId))
      }
    }

  def chapterMeta(id: String, chapterId: String) =
    Open { _ =>
      env.study.chapterRepo.byId(chapterId).map {
        _.filter(_.studyId.value == id) ?? { chapter =>
          Ok(env.study.jsonView.chapterConfig(chapter))
        }
      }
    }

  private[controllers] def chatOf(study: lila.study.Study)(implicit ctx: Context) = {
    ctx.noKid && ctx.noBot && // no public chats for kids and bots
    ctx.me.fold(true) {       // anon can see public chats
      env.chat.panic.allowed
    }
  } ?? env.chat.api.userChat
    .findMine(Chat.Id(study.id.value), ctx.me)
    .dmap(some)
    .mon(_.chat.fetch("study"))

  def createAs =
    AuthBody { implicit ctx => me =>
      implicit val req = ctx.body
      lila.study.StudyForm.importGame.form
        .bindFromRequest()
        .fold(
          _ => Redirect(routes.Study.byOwnerDefault(me.username)).fuccess,
          data =>
            for {
              owner   <- env.study.studyRepo.recentByOwner(me.id, 50)
              contrib <- env.study.studyRepo.recentByContributor(me.id, 50)
              res <-
                if (owner.isEmpty && contrib.isEmpty) createStudy(data, me)
                else {
                  val back = HTTPRequest.referer(req) orElse
                    data.fen.map(fen => editorC.editorUrl(fen, data.variant | chess.variant.Variant.default))
                  Ok(html.study.create(data, owner, contrib, back)).fuccess
                }
            } yield res
        )
    }

  def create =
    AuthBody { implicit ctx => me =>
      implicit val req = ctx.body
      lila.study.StudyForm.importGame.form
        .bindFromRequest()
        .fold(
          _ => Redirect(routes.Study.byOwnerDefault(me.username)).fuccess,
          data => createStudy(data, me)
        )
    }

  private def createStudy(data: lila.study.StudyForm.importGame.Data, me: lila.user.User)(implicit
      ctx: Context
  ) =
    env.study.api.importGame(lila.study.StudyMaker.ImportGame(data), me, ctx.pref.showRatings) flatMap {
      _.fold(notFound) { sc =>
        Redirect(routes.Study.chapter(sc.study.id.value, sc.chapter.id.value)).fuccess
      }
    }

  def delete(id: String) =
    Auth { _ => me =>
      env.study.api.byIdAndOwnerOrAdmin(id, me) flatMap {
        _ ?? { study =>
          env.study.api.delete(study) >> env.relay.api.deleteRound(lila.relay.RelayRound.Id(id)).map {
            case None       => Redirect(routes.Study.mine("hot"))
            case Some(tour) => Redirect(routes.RelayTour.redirectOrApiTour(tour.slug, tour.id.value))
          }
        }
      }
    }

  def clearChat(id: String) =
    Auth { _ => me =>
      env.study.api.isOwnerOrAdmin(id, me) flatMap {
        _ ?? env.chat.api.userChat.clear(Chat.Id(id))
      } inject Redirect(routes.Study.show(id))
    }

  def importPgn(id: String) =
    AuthBody { implicit ctx => me =>
      implicit val req = ctx.body
      get("sri") ?? { sri =>
        lila.study.StudyForm.importPgn.form
          .bindFromRequest()
          .fold(
            jsonFormError,
            data =>
              env.study.api.importPgns(
                StudyModel.Id(id),
                data.toChapterDatas,
                sticky = data.sticky,
                ctx.pref.showRatings
              )(Who(me.id, lila.socket.Socket.Sri(sri)))
          )
      }
    }

  def admin(id: String) =
    Secure(_.StudyAdmin) { ctx => me =>
      env.study.api.adminInvite(id, me) inject (if (HTTPRequest isXhr ctx.req) NoContent
                                                else Redirect(routes.Study.show(id)))
    }

  def embed(id: String, chapterId: String) =
    Action.async { implicit req =>
      val studyWithChapter =
        if (chapterId == "autochap") env.study.api.byIdWithChapter(id)
        else env.study.api.byIdWithChapter(id, chapterId)
      studyWithChapter.map(_.filterNot(_.study.isPrivate)) flatMap {
        _.fold(embedNotFound) { case WithChapter(study, chapter) =>
          for {
            chapters <- env.study.chapterRepo.idNames(study.id)
            studyJson <- env.study.jsonView(
              study.copy(
                members = lila.study.StudyMembers(Map.empty) // don't need no members
              ),
              List(chapter.metadata),
              chapter,
              none
            )
            setup      = chapter.setup
            initialFen = chapter.root.fen.some
            pov        = userAnalysisC.makePov(initialFen, setup.variant)
            baseData = env.round.jsonView.userAnalysisJson(
              pov,
              lila.pref.Pref.default,
              initialFen,
              setup.orientation,
              owner = false,
              me = none
            )
            analysis = baseData ++ Json.obj(
              "treeParts" -> partitionTreeJsonWriter.writes {
                lila.study.TreeBuilder.makeRoot(chapter.root, setup.variant)
              }
            )
            data = lila.study.JsonView.JsData(study = studyJson, analysis = analysis)
            result <- negotiate(
              html = Ok(html.study.embed(study, chapter, chapters, data)).fuccess,
              api = _ => Ok(Json.obj("study" -> data.study, "analysis" -> data.analysis)).fuccess
            )
          } yield result
        }
      } map NoCache
    }

  private def embedNotFound(implicit req: RequestHeader): Fu[Result] =
    fuccess(NotFound(html.study.embed.notFound))

  def cloneStudy(id: String) =
    Auth { implicit ctx => _ =>
      OptionFuResult(env.study.api.byId(id)) { study =>
        CanView(study, ctx.me) {
          Ok(html.study.clone(study)).fuccess
        }(privateUnauthorizedFu(study), privateForbiddenFu(study))
      }
    }

  private val CloneLimitPerUser = new lila.memo.RateLimit[lila.user.User.ID](
    credits = 10 * 3,
    duration = 24.hour,
    key = "study.clone.user"
  )

  private val CloneLimitPerIP = new lila.memo.RateLimit[IpAddress](
    credits = 20 * 3,
    duration = 24.hour,
    key = "study.clone.ip"
  )

  def cloneApply(id: String) =
    Auth { implicit ctx => me =>
      val cost = if (isGranted(_.Coach) || me.hasTitle) 1 else 3
      CloneLimitPerUser(me.id, cost = cost) {
        CloneLimitPerIP(ctx.ip, cost = cost) {
          OptionFuResult(env.study.api.byId(id)) { prev =>
            CanView(prev, me.some) {
              env.study.api.clone(me, prev) map { study =>
                Redirect(routes.Study.show((study | prev).id.value))
              }
            }(privateUnauthorizedFu(prev), privateForbiddenFu(prev))
          }
        }(rateLimitedFu)
      }(rateLimitedFu)
    }

  private val PgnRateLimitPerIp = new lila.memo.RateLimit[IpAddress](
    credits = 31,
    duration = 1.minute,
    key = "export.study.pgn.ip"
  )

  def pgn(id: String) =
    Open { implicit ctx =>
      PgnRateLimitPerIp(ctx.ip, msg = id) {
        OptionFuResult(env.study.api byId id) { study =>
          CanView(study, ctx.me) {
            doPgn(study, ctx.req).fuccess
          }(privateUnauthorizedFu(study), privateForbiddenFu(study))
        }
      }(rateLimitedFu)
    }

  def apiPgn(id: String) = AnonOrScoped(_.Study.Read) { req => me =>
    env.study.api.byId(id).map {
      _.fold(NotFound(jsonError("Study not found"))) { study =>
        PgnRateLimitPerIp(HTTPRequest ipAddress req, msg = id) {
          CanView(study, me) {
            doPgn(study, req)
          }(privateUnauthorizedJson, privateForbiddenJson)
        }(rateLimitedJson)
      }
    }
  }

  private def doPgn(study: StudyModel, req: RequestHeader) = {
    Ok.chunked(env.study.pgnDump(study, requestPgnFlags(req)))
      .pipe(asAttachmentStream(s"${env.study.pgnDump filename study}.pgn"))
      .as(pgnContentType)
  }

  def chapterPgn(id: String, chapterId: String) =
    Open { implicit ctx =>
      env.study.api.byIdWithChapter(id, chapterId) flatMap {
        _.fold(notFound) { case WithChapter(study, chapter) =>
          CanView(study, ctx.me) {
            env.study.pgnDump.ofChapter(study, requestPgnFlags(ctx.req))(chapter) map { pgn =>
              Ok(pgn.toString)
                .pipe(asAttachment(s"${env.study.pgnDump.filename(study, chapter)}.pgn"))
                .as(pgnContentType)
            }
          }(privateUnauthorizedFu(study), privateForbiddenFu(study))
        }
      }
    }

  def exportPgn(username: String) =
    OpenOrScoped(_.Study.Read)(
      open = ctx => handleExport(username, ctx.me, ctx.req),
      scoped = req => me => handleExport(username, me.some, req)
    )

  private def handleExport(
      username: String,
      me: Option[lila.user.User],
      req: RequestHeader
  ) = {
    val name   = if (username == "me") me.fold("me")(_.username) else username
    val userId = lila.user.User normalize name
    val flags  = requestPgnFlags(req)
    val isMe   = me.exists(_.id == userId)
    apiC
      .GlobalConcurrencyLimitPerIpAndUserOption(req, me) {
        env.study.studyRepo
          .sourceByOwner(userId, isMe)
          .flatMapConcat(env.study.pgnDump(_, flags))
          .withAttributes(
            akka.stream.ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider)
          )
      } { source =>
        Ok.chunked(source)
          .pipe(asAttachmentStream(s"${name}-${if (isMe) "all" else "public"}-studies.pgn"))
          .as(pgnContentType)
      }
      .fuccess
  }

  private def requestPgnFlags(req: RequestHeader) =
    lila.study.PgnDump.WithFlags(
      comments = getBoolOpt("comments", req) | true,
      variations = getBoolOpt("variations", req) | true,
      clocks = getBoolOpt("clocks", req) | true
    )

  def chapterGif(id: String, chapterId: String, theme: Option[String], piece: Option[String]) =
    Open { implicit ctx =>
      env.study.api.byIdWithChapter(id, chapterId) flatMap {
        _.fold(notFound) { case WithChapter(study, chapter) =>
          CanView(study, ctx.me) {
            env.study.gifExport.ofChapter(chapter, theme, piece) map { stream =>
              Ok.chunked(stream)
                .pipe(asAttachmentStream(s"${env.study.pgnDump.filename(study, chapter)}.gif"))
                .as("image/gif")
            } recover { case lila.base.LilaInvalid(msg) =>
              BadRequest(msg)
            }
          }(privateUnauthorizedFu(study), privateForbiddenFu(study))
        }
      }
    }

  def multiBoard(id: String, page: Int) =
    Open { implicit ctx =>
      OptionFuResult(env.study.api byId id) { study =>
        CanView(study, ctx.me) {
          env.study.multiBoard.json(study.id, page, getBool("playing")) map JsonOk
        }(privateUnauthorizedJson.fuccess, privateForbiddenJson.fuccess)
      }
    }

  def topicAutocomplete =
    Action.async { req =>
      get("term", req).filter(_.nonEmpty) match {
        case None => BadRequest("No search term provided").fuccess
        case Some(term) =>
          import lila.study.JsonView._
          env.study.topicApi.findLike(term, get("user", req)) map { JsonOk(_) }
      }
    }

  def topics =
    Open { implicit ctx =>
      env.study.topicApi.popular(50) zip
        ctx.me.??(u => env.study.topicApi.userTopics(u.id) dmap some) map { case (popular, mine) =>
          val form = mine map lila.study.StudyForm.topicsForm
          Ok(html.study.topic.index(popular, mine, form))
        }
    }

  def setTopics =
    AuthBody { implicit ctx => me =>
      implicit val req = ctx.body
      lila.study.StudyForm.topicsForm
        .bindFromRequest()
        .fold(
          _ => Redirect(routes.Study.topics).fuccess,
          topics =>
            env.study.topicApi.userTopics(me, topics) inject
              Redirect(routes.Study.topics)
        )
    }

  def staffPicks = Open { implicit ctx =>
    pageHit
    OptionOk(prismicC getBookmark "studies-staff-picks") { case (doc, resolver) =>
      html.study.list.staffPicks(doc, resolver)
    }
  }

  def privateUnauthorizedJson = Unauthorized(jsonError("This study is now private"))
  def privateUnauthorizedFu(study: StudyModel)(implicit ctx: lila.api.Context) =
    negotiate(
      html = fuccess(Unauthorized(html.site.message.privateStudy(study))),
      api = _ => fuccess(privateUnauthorizedJson)
    )

  def privateForbiddenJson = Forbidden(jsonError("This study is now private"))
  def privateForbiddenFu(study: StudyModel)(implicit ctx: lila.api.Context) =
    negotiate(
      html = fuccess(Forbidden(html.site.message.privateStudy(study))),
      api = _ => fuccess(privateForbiddenJson)
    )

  def CanView[A](study: StudyModel, me: Option[lila.user.User])(
      f: => A
  )(unauthorized: => A, forbidden: => A): A =
    me match {
      case _ if !study.isPrivate                     => f
      case None                                      => unauthorized
      case Some(me) if study.members.contains(me.id) => f
      case _                                         => forbidden
    }

  implicit private def makeStudyId(id: String): StudyModel.Id = StudyModel.Id(id)
  implicit private def makeChapterId(id: String): Chapter.Id  = Chapter.Id(id)

  private[controllers] def streamersOf(study: StudyModel) = streamerCache get study.id

  private val streamerCache =
    env.memo.cacheApi[StudyModel.Id, List[lila.user.User.ID]](64, "study.streamers") {
      _.refreshAfterWrite(15.seconds)
        .maximumSize(512)
        .buildAsyncFuture { studyId =>
          env.study.studyRepo.membersById(studyId) flatMap {
            _.map(_.members).filter(_.nonEmpty) ?? { members =>
              env.streamer.liveStreamApi.all.flatMap {
                _.streams
                  .filter { s =>
                    members.exists(m => s is m._2.id)
                  }
                  .map { stream =>
                    env.study.isConnected(studyId, stream.streamer.userId) map {
                      _ option stream.streamer.userId
                    }
                  }
                  .sequenceFu
                  .dmap(_.flatten)
              }
            }
          }
        }
    }

  def glyphs(lang: String) = Action {
    import chess.format.pgn.Glyph
    import lila.tree.Node.glyphWriter
    import lila.i18n.{ I18nKeys => trans }

    play.api.i18n.Lang.get(lang) ?? { implicit lang =>
      JsonOk(
        Json.obj(
          "move" -> List(
            Glyph.MoveAssessment.good.copy(name = trans.study.goodMove.txt()),
            Glyph.MoveAssessment.mistake.copy(name = trans.study.mistake.txt()),
            Glyph.MoveAssessment.brillant.copy(name = trans.study.brilliantMove.txt()),
            Glyph.MoveAssessment.blunder.copy(name = trans.study.blunder.txt()),
            Glyph.MoveAssessment.interesting.copy(name = trans.study.interestingMove.txt()),
            Glyph.MoveAssessment.dubious.copy(name = trans.study.dubiousMove.txt()),
            Glyph.MoveAssessment.only.copy(name = trans.study.onlyMove.txt()),
            Glyph.MoveAssessment.zugzwang.copy(name = trans.study.zugzwang.txt())
          ),
          "position" -> List(
            Glyph.PositionAssessment.equal.copy(name = trans.study.equalPosition.txt()),
            Glyph.PositionAssessment.unclear.copy(name = trans.study.unclearPosition.txt()),
            Glyph.PositionAssessment.whiteSlightlyBetter
              .copy(name = trans.study.whiteIsSlightlyBetter.txt()),
            Glyph.PositionAssessment.blackSlightlyBetter
              .copy(name = trans.study.blackIsSlightlyBetter.txt()),
            Glyph.PositionAssessment.whiteQuiteBetter.copy(name = trans.study.whiteIsBetter.txt()),
            Glyph.PositionAssessment.blackQuiteBetter.copy(name = trans.study.blackIsBetter.txt()),
            Glyph.PositionAssessment.whiteMuchBetter.copy(name = trans.study.whiteIsWinning.txt()),
            Glyph.PositionAssessment.blackMuchBetter.copy(name = trans.study.blackIsWinning.txt())
          ),
          "observation" -> List(
            Glyph.Observation.novelty.copy(name = trans.study.novelty.txt()),
            Glyph.Observation.development.copy(name = trans.study.development.txt()),
            Glyph.Observation.initiative.copy(name = trans.study.initiative.txt()),
            Glyph.Observation.attack.copy(name = trans.study.attack.txt()),
            Glyph.Observation.counterplay.copy(name = trans.study.counterplay.txt()),
            Glyph.Observation.timeTrouble.copy(name = trans.study.timeTrouble.txt()),
            Glyph.Observation.compensation.copy(name = trans.study.withCompensation.txt()),
            Glyph.Observation.withIdea.copy(name = trans.study.withTheIdea.txt())
          )
        )
      ).withHeaders(CACHE_CONTROL -> "max-age=3600")
    }
  }
}
