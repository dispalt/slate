package qq
package cc

import qq.data.CompiledDefinition

import scalaz.{Monoid, Plus, PlusEmpty}
import scalaz.syntax.either._

object Prelude {
  implicit def preludeMonoid: PlusEmpty[Prelude] = new PlusEmpty[Prelude] {
    override def empty[J]: Prelude[J] = new Prelude[J] {
      override def all(runtime: QQRuntime[J]): OrCompilationError[IndexedSeq[CompiledDefinition[J]]] = IndexedSeq.empty.right
    }

    override def plus[J](f1: Prelude[J], f2: => Prelude[J]): Prelude[J] = new Prelude[J] {
      override def all(runtime: QQRuntime[J]): OrCompilationError[IndexedSeq[CompiledDefinition[J]]] = for {
        fst <- f1.all(runtime)
        snd <- f2.all(runtime)
      } yield fst ++ snd
    }
  }
}

trait Prelude[J] {
  def all(runtime: QQRuntime[J]): OrCompilationError[IndexedSeq[CompiledDefinition[J]]]
}
