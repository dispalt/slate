package dash.ajax

sealed trait AjaxMethod
case object PUT extends AjaxMethod
case object POST extends AjaxMethod
case object GET extends AjaxMethod
case object PATCH extends AjaxMethod

object AjaxMethod {
  def asString(meth: AjaxMethod): String = meth match {
    case GET => "GET"
    case PATCH => "PATCH"
    case POST => "POST"
    case PUT => "PUT"
  }
}