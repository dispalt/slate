package dash
package app

import qq.CompiledDefinition

object DashPrelude {

  import CompiledDefinition.noParamDefinition

  def googleAuth: CompiledDefinition[Any] =
    noParamDefinition("googleAuth", _ => _ => identify.getAuthToken(interactive = false).map(_ :: Nil))

  val all = googleAuth +: Vector.empty
}
