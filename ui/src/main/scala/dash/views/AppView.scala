package dash
package views

import dash.models.AppModel
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.{ReactComponentB, ReactElement, ReactNode, TopNode}
import monix.execution.Scheduler
import dash.Util.observableReusability
import dash.views.ExpandableContentView.ExpandableContentProps

import scalacss.Defaults._
import scalaz.\/
import scalaz.syntax.either._

object AppView {

  object Styles extends StyleSheet.Inline {

    import dsl._
    import scala.language.postfixOps

    val panel = style(
      marginLeft(10 px),
      marginRight(10 px),
      marginBottom(15 px),
      addClassNames("mdl-cell", "mdl-card", "mdl-shadow--4dp", "mdl-cell--12-col", "mdl-color--grey-100", "mdl-color-text--grey-600")
    )

    val header = style(
      addClassName("mdl-card__title"),
//      width(100 %%),
      marginTop(-10 px),
      display.inlineBlock
    )

    val title = style(
      addClassName("mdl-color-text--grey-700"),
      addClassName("mdl-card__title-text"),
      (textDecoration := "none").important,
      fontWeight._700,
      letterSpacing(1 px),
      fontFamily(dash.views.Styles.akrobatBlack),
      display inline
    )

    val animationGroup = new dash.views.ScrollFadeContainer("filter-group")

  }

  final case class AppState(model: AppModel)

  object AppState {
    implicit val reusability: Reusability[AppState] =
      Reusability.caseClass[AppState]
  }

  final case class AppProps(id: Int, title: String, titleLink: String, model: AppModel)

  object AppProps {
    implicit val reusability: Reusability[AppProps] =
      Reusability.byRefOr_==
  }

  def builder(implicit sch: Scheduler
             ): ReactComponentB[AppProps, AppState, Unit, TopNode] = {
    import japgolly.scalajs.react.vdom.all._

    import scalacss.ScalaCssReact._

    ReactComponentB[AppProps]("Expandable content view")
      .initialState[AppState](AppState(AppModel(Nil.right)))
      .renderP { (_, props) =>
        div(Styles.panel, key := props.id,
          div(Styles.header,
            div(ExpandableContentView.Styles.headerLeft,
              a(Styles.title, props.title.toUpperCase(), href := props.titleLink)
            )
          ),
          div( // `class` := "mdl-grid",
            Styles.animationGroup(
              props.model.content.fold[List[ReactNode]]({ ex =>
                ErrorView.builder.build(ex) :: Nil
              }, _.map { model =>
                ExpandableContentView.builder.build(ExpandableContentProps(model, initiallyExpanded = false))
              }): _*
            )
          )
        )
      }
      .configure(Reusability.shouldComponentUpdate)
  }


}
