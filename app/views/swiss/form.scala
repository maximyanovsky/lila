package views.html.swiss

import controllers.routes
import play.api.data.Form

import lila.api.Context
import lila.app.templating.Environment._
import lila.app.ui.ScalatagsTemplate._
import lila.hub.LightTeam.TeamID
import lila.swiss.{ Swiss, SwissCondition, SwissForm }
import lila.tournament.TournamentForm

object form {

  def create(form: Form[_], teamId: TeamID)(implicit ctx: Context) =
    views.html.base.layout(
      title = trans.swiss.newSwiss.txt(),
      moreCss = cssTag("swiss.form"),
      moreJs = jsModule("tourForm")
    ) {
      val fields = new SwissFields(form, none)
      main(cls := "page-small")(
        div(cls := "swiss__form tour__form box box-pad")(
          h1(cls := "box__top")(trans.swiss.newSwiss()),
          postForm(cls := "form3", action := routes.Swiss.create(teamId))(
            form3.split(fields.name, fields.nbRounds),
            form3.split(fields.description, fields.rated),
            fields.clock,
            form3.split(fields.roundInterval, fields.startsAt),
            advancedSettings(
              form3.split(fields.variant, fields.position),
              form3.split(fields.chatFor, fields.entryCode),
              condition(form, fields, swiss = none),
              form3.split(fields.forbiddenPairings, fields.allowList),
              form3.split(fields.manualPairings)
            ),
            form3.globalError(form),
            form3.actions(
              a(href := routes.Team.show(teamId))(trans.cancel()),
              form3.submit(trans.createANewTournament(), icon = "".some)
            )
          )
        )
      )
    }

  def edit(swiss: Swiss, form: Form[_])(implicit ctx: Context) =
    views.html.base.layout(
      title = swiss.name,
      moreCss = cssTag("swiss.form"),
      moreJs = jsModule("tourForm")
    ) {
      val fields = new SwissFields(form, swiss.some)
      main(cls := "page-small")(
        div(cls := "swiss__form box box-pad")(
          h1(cls := "box__top")("Edit ", swiss.name),
          postForm(cls := "form3", action := routes.Swiss.update(swiss.id.value))(
            form3.split(fields.name, fields.nbRounds),
            form3.split(fields.description, fields.rated),
            fields.clock,
            form3.split(fields.roundInterval, swiss.isCreated option fields.startsAt),
            advancedSettings(
              form3.split(fields.variant, fields.position),
              form3.split(fields.chatFor, fields.entryCode),
              condition(form, fields, swiss = swiss.some),
              form3.split(fields.forbiddenPairings, fields.allowList),
              form3.split(fields.manualPairings)
            ),
            form3.globalError(form),
            form3.actions(
              a(href := routes.Swiss.show(swiss.id.value))(trans.cancel()),
              form3.submit(trans.save(), icon = "".some)
            )
          ),
          postForm(cls := "terminate", action := routes.Swiss.terminate(swiss.id.value))(
            submitButton(dataIcon := "", cls := "text button button-red confirm")(
              trans.cancelTournament()
            )
          )
        )
      )
    }

  private def advancedSettings(settings: Frag*) =
    details(summary("Advanced settings"), settings)

  private def condition(form: Form[_], fields: SwissFields, swiss: Option[Swiss])(implicit ctx: Context) =
    frag(
      form3.split(
        form3.group(form("conditions.nbRatedGame.nb"), trans.minimumRatedGames(), half = true)(
          form3.select(_, SwissCondition.DataForm.nbRatedGameChoices)
        ),
        (ctx.me.exists(_.hasTitle) || isGranted(_.ManageTournament)) ?? {
          form3.checkbox(
            form("conditions.titled"),
            trans.onlyTitled(),
            help = trans.onlyTitledHelp().some,
            half = true
          )
        }
      ),
      form3.split(
        form3.group(form("conditions.minRating.rating"), trans.minimumRating(), half = true)(
          form3.select(_, SwissCondition.DataForm.minRatingChoices)
        ),
        form3.group(form("conditions.maxRating.rating"), trans.maximumWeeklyRating(), half = true)(
          form3.select(_, SwissCondition.DataForm.maxRatingChoices)
        )
      )
    )
}

final private class SwissFields(form: Form[_], swiss: Option[Swiss])(implicit ctx: Context) {

  private def disabledAfterStart = swiss.exists(!_.isCreated)

  def name =
    form3.group(form("name"), trans.name()) { f =>
      div(
        form3.input(f),
        small(cls := "form-help")(
          trans.safeTournamentName(),
          br,
          trans.inappropriateNameWarning(),
          br,
          trans.emptyTournamentName()
        )
      )
    }
  def nbRounds =
    form3.group(
      form("nbRounds"),
      trans.swiss.numberOfRounds(),
      help = trans.swiss.numberOfRoundsHelp().some,
      half = true
    )(
      form3.input(_, typ = "number")
    )

  def rated =
    frag(
      form3.checkbox(
        form("rated"),
        trans.rated(),
        help = trans.ratedFormHelp().some,
        half = true
      ),
      st.input(tpe := "hidden", st.name := form("rated").name, value := "false") // hack allow disabling rated
    )
  def variant =
    form3.group(form("variant"), trans.variant(), half = true)(
      form3.select(
        _,
        translatedVariantChoicesWithVariants(_.key).map(x => x._1 -> x._2),
        disabled = disabledAfterStart
      )
    )
  def clock =
    form3.split(
      form3.group(form("clock.limit"), trans.clockInitialTime(), half = true)(
        form3.select(_, SwissForm.clockLimitChoices, disabled = disabledAfterStart)
      ),
      form3.group(form("clock.increment"), trans.clockIncrement(), half = true)(
        form3.select(_, TournamentForm.clockIncrementChoices, disabled = disabledAfterStart)
      )
    )
  def roundInterval =
    form3.group(form("roundInterval"), trans.swiss.roundInterval(), half = true)(
      form3.select(_, SwissForm.roundIntervalChoices)
    )
  def description =
    form3.group(
      form("description"),
      trans.tournDescription(),
      help = trans.tournDescriptionHelp().some,
      half = true
    )(form3.textarea(_)(rows := 4))
  def position =
    form3.group(
      form("position"),
      trans.startPosition(),
      klass = "position",
      half = true,
      help =
        trans.positionInputHelp(a(href := routes.Editor.index, targetBlank)(trans.boardEditor.txt())).some
    )(form3.input(_))
  def startsAt =
    form3.group(
      form("startsAt"),
      trans.swiss.tournStartDate(),
      help = trans.inYourLocalTimezone().some,
      half = true
    )(form3.flatpickr(_))

  def chatFor =
    form3.group(form("chatFor"), trans.tournChat(), half = true) { f =>
      form3.select(
        f,
        Seq(
          Swiss.ChatFor.NONE    -> trans.noChat.txt(),
          Swiss.ChatFor.LEADERS -> trans.onlyTeamLeaders.txt(),
          Swiss.ChatFor.MEMBERS -> trans.onlyTeamMembers.txt(),
          Swiss.ChatFor.ALL     -> trans.study.everyone.txt()
        )
      )
    }

  def entryCode =
    form3.group(
      form("password"),
      trans.tournamentEntryCode(),
      help = trans.makePrivateTournament().some,
      half = true
    )(form3.input(_)(autocomplete := "off"))

  def forbiddenPairings =
    form3.group(
      form("forbiddenPairings"),
      trans.swiss.forbiddenPairings(),
      help = trans.swiss.forbiddenPairingsHelp().some,
      half = true
    )(form3.textarea(_)(rows := 4))

  def manualPairings =
    form3.group(
      form("manualPairings"),
      "Manual pairings in next round",
      help = frag(
        "Specify all pairings of the next round manually. One player pair per line. Example:",
        br,
        "PlayerA PlayerB",
        br,
        "PlayerC PlayerD",
        br,
        "Missing players will be given a bye, which is worth 1 point.",
        br,
        "Leave empty to let lichess create pairings automatically."
      ).some,
      half = true
    )(form3.textarea(_)(rows := 4))

  def allowList = form3.group(
    form("conditions.allowList"),
    "Only allow pre-defined users to join",
    help = raw(
      "If this list is non-empty, then usernames absent from this list will be forbidden to join. One username per line."
    ).some,
    half = true
  )(form3.textarea(_)(rows := 4))
}
