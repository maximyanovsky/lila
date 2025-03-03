package views
package html.puzzle

import controllers.routes

import lila.api.Context
import lila.app.templating.Environment._
import lila.app.ui.ScalatagsTemplate._
import lila.common.LilaOpeningFamily
import lila.puzzle.PuzzleOpening.Order
import lila.puzzle.{ Puzzle, PuzzleAngle, PuzzleOpening, PuzzleOpeningCollection, PuzzleTheme }

object opening {

  def all(openings: PuzzleOpeningCollection, mine: Option[PuzzleOpening.Mine], order: Order)(implicit
      ctx: Context
  ) =
    views.html.base.layout(
      title = "Puzzles by openings",
      moreCss = cssTag("puzzle.page"),
      moreJs = jsModule("puzzle.opening")
    )(
      main(cls := "page-menu")(
        bits.pageMenu("openings", ctx.me),
        div(cls := "page-menu__content box")(
          boxTop(
            h1("Puzzles by openings"),
            orderSelect(order)
          ),
          mine.isEmpty option frag(
            p(cls := "help help-touchscreen")(
              iconTag("", "Use \"Find in page\" in the browser menu to find your favourite opening!")
            ),
            p(cls := "help help-keyboard")(iconTag("", "Use Ctrl+f to find your favourite opening!"))
          ),
          div(cls := "puzzle-themes")(
            div(cls := "puzzle-openings")(
              mine.filter(_.families.nonEmpty) map { m =>
                div(cls := "puzzle-openings__mine")(
                  h2("Openings you played the most in rated games"),
                  div(cls := "puzzle-openings__list")(m.families take 12 map {
                    familyLink(_, mine)(cls := "puzzle-openings__link")
                  })
                )
              },
              treeOf(openings treeList order, mine),
              theme.info
            )
          )
        )
      )
    )

  private[puzzle] def listOf(families: List[PuzzleOpening.FamilyWithCount])(implicit ctx: Context) =
    div(cls := "puzzle-openings__list")(families map { fam =>
      a(cls := "puzzle-openings__link", href := routes.Puzzle.show(fam.family.key.value))(
        h3(
          fam.family.name,
          em(fam.count.localize)
        )
      )
    })

  private def treeOf(openings: PuzzleOpening.TreeList, mine: Option[PuzzleOpening.Mine])(implicit
      ctx: Context
  ) =
    openings map { case (fam, openings) =>
      div(cls := "puzzle-openings__tree__family")(
        h2(
          familyLink(fam.family, mine),
          em(fam.count.localize)
        ),
        openings.nonEmpty option div(cls := "puzzle-openings__list")(openings.map { op =>
          a(
            dataFen := op.opening.ref.fen,
            cls := List(
              "blpt puzzle-openings__link" -> true,
              "opening-mine"               -> mine.exists(_.variationKeys(op.opening.key))
            ),
            href := routes.Puzzle.show(op.opening.key.value)
          )(
            h3(
              op.opening.variation.name,
              em(op.count.localize)
            )
          )
        })
      )
    }

  private def familyLink(family: LilaOpeningFamily, mine: Option[PuzzleOpening.Mine]): Tag = a(
    cls     := List("blpt" -> true, "opening-mine" -> mine.exists(_.familyKeys(family.key))),
    dataFen := family.full.map(_.fen)
  )(href := routes.Puzzle.show(family.key.value))(family.name)

  def orderSelect(order: Order)(implicit ctx: Context) = {
    views.html.base.bits.mselect(
      "orders",
      span(order.name()),
      Order.all map { o =>
        a(href := routes.Puzzle.openings(o.key), cls := (order == o).option("current"))(o.name())
      }
    )
  }
}
