package lila.forum

import lila.common.Form.cleanText
import play.api.data._
import play.api.data.Forms._
import lila.user.User

final private[forum] class ForumForm(
    promotion: lila.security.PromotionApi,
    val captcher: lila.hub.actors.Captcher
)(implicit ec: scala.concurrent.ExecutionContext)
    extends lila.hub.CaptchedForm {

  import ForumForm._

  def postMapping(user: User, inOwnTeam: Boolean) =
    mapping(
      "text"    -> userTextMapping(user, inOwnTeam),
      "gameId"  -> text,
      "move"    -> text,
      "modIcon" -> optional(boolean)
    )(PostData.apply)(PostData.unapply)
      .verifying(captchaFailMessage, validateCaptcha _)

  def post(user: User, inOwnTeam: Boolean) = Form(postMapping(user, inOwnTeam))

  def postEdit(user: User, inOwnTeam: Boolean, previousText: String) =
    Form(
      mapping(
        "changes" -> userTextMapping(user, inOwnTeam, previousText.some)
      )(PostEdit.apply)(PostEdit.unapply)
    )

  def postWithCaptcha(user: User, inOwnTeam: Boolean) = withCaptcha(post(user, inOwnTeam))

  def topic(user: User, inOwnTeam: Boolean) =
    Form(
      mapping(
        "name" -> cleanText(minLength = 3, maxLength = 100),
        "post" -> postMapping(user, inOwnTeam)
      )(TopicData.apply)(TopicData.unapply)
    )

  val deleteWithReason = Form(
    single("reason" -> optional(nonEmptyText))
  )

  private def userTextMapping(user: User, inOwnTeam: Boolean, previousText: Option[String] = None) =
    cleanText(minLength = 3)
      .verifying(
        "You have reached the daily maximum for links in forum posts.",
        t => inOwnTeam || promotion.test(user)(t, previousText)
      )
}

object ForumForm {

  case class PostData(
      text: String,
      gameId: String,
      move: String,
      modIcon: Option[Boolean]
  )

  case class TopicData(
      name: String,
      post: PostData
  )

  case class PostEdit(changes: String)
}
