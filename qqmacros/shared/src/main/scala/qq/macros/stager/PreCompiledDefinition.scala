package qq.macros.stager

import qq.cc.OrCompilationError

import scala.reflect.macros.Universe

case class PreCompiledDefinition[C <: Universe]
(name: String, numParams: Int, body: Vector[C#Tree] => OrCompilationError[C#Tree])