package slate
package storage

import cats.data._
import cats.implicits._
import cats.{Monad, Monoid, ~>}
import monix.eval.Task
import org.atnos.eff.Eff._
import org.atnos.eff._
import org.atnos.eff.syntax.all._
import org.scalajs.dom.ext.{LocalStorage, SessionStorage, Storage => SStorage}

// Operations on a Storage with F[_] effects
// To abstract over text key-value storage that has different effects performed by its operations
// Examples of uses:
// localStorage, sessionStorage: use F = Task
// pure: Use F = State[Map[String, String], ?]]
abstract class Storage[F[_]] {
  def apply(key: String): F[Option[String]]

  def update(key: String, data: String): F[Unit]

  def remove(key: String): F[Unit]
}

object Storage {

  // finally tagless encoding isomorphism
  def storageToStorageActionTrans[F[_]](stor: Storage[F]): StorageAction ~> F = new (StorageAction ~> F) {
    override def apply[A](fa: StorageAction[A]): F[A] = fa.run(stor)
  }
  def storageFromStorageActionTrans[F[_]](nat: StorageAction ~> F): Storage[F] = new Storage[F] {
    override def apply(key: String): F[Option[String]] = nat(StorageAction.Get(key))
    override def update(key: String, data: String): F[Unit] = nat(StorageAction.Update(key, data))
    override def remove(key: String): F[Unit] = nat(StorageAction.Remove(key))
  }
}

// Finally tagless storage action functor (http://okmij.org/ftp/tagless-final/)
// Only using this because higher-kinded GADT refinement is broken,
// but dynamic dispatch is better than pattern matching on JS anyway.
sealed abstract class StorageAction[T] {
  def run[F[_]](storage: Storage[F]): F[T]
}

object StorageAction {

  final case class Get(key: String) extends StorageAction[Option[String]] {
    override def run[F[_]](storage: Storage[F]): F[Option[String]] = storage(key)
  }

  final case class Update(key: String, value: String) extends StorageAction[Unit] {
    override def run[F[_]](storage: Storage[F]): F[Unit] = storage.update(key, value)
  }

  final case class Remove(key: String) extends StorageAction[Unit] {
    override def run[F[_]](storage: Storage[F]): F[Unit] = storage.remove(key)
  }

  type _storageAction[R] = StorageAction <= R
  type _StorageAction[R] = StorageAction |= R

}

// DSL methods
object StorageProgram {

  import StorageAction._

  def get[F: _StorageAction](key: String): Eff[F, Option[String]] =
    Get(key).send[F]

  def update[F: _StorageAction](key: String, value: String): Eff[F, Unit] =
    Update(key, value).send[F]

  def remove[F: _StorageAction](key: String): Eff[F, Unit] =
    Remove(key).send[F]

  def getOrSet[F: _StorageAction](key: String, value: => String): Eff[F, String] = {
    for {
      cur <- get(key)
      result <- cur.fold(update[F](key, value).as(value))(_.pureEff[F])
    } yield result
  }

  def runProgram[S[_] : Monad, O, I, U, A](storage: Storage[S], program: Eff[I, A])
                                          (implicit ev: Member.Aux[StorageAction, I, U], ev2: Member.Aux[S, O, U]): Eff[O, A] = {
    interpret.transform(program, Storage.storageToStorageActionTrans(storage))
  }

  def runProgramInto[S[_] : Monad, I, U, A](storage: Storage[S], program: Eff[I, A])
                                           (implicit ev: Member.Aux[StorageAction, I, U], ev2: Member[S, U]): Eff[U, A] = {
    interpret.translateNat(program)(new (StorageAction ~> Eff[U, ?]) {
      override def apply[X](fa: StorageAction[X]): Eff[U, X] = fa.run(storage).send[U]
    })
  }

  def printAction: Storage[λ[A => String]] = new Storage[λ[A => String]] {
    override def apply(key: String): String = s"get($key)"
    override def update(key: String, data: String): String = s"update($key, $data)"
    override def remove(key: String): String = s"remove($key)"
  }

  def logProgram[I, O, L: Monoid, U, A](eff: Eff[I, A])(log: StorageAction ~> λ[P => L])
                                       (implicit ev: Member.Aux[StorageAction, I, U],
                                        ev2: Member[StorageAction, O],
                                        ev3: MemberIn[Writer[L, ?], O],
                                        ev4: IntoPoly[I, O]): Eff[O, A] = {
    interpret.translateInto[I, StorageAction, O, A](eff)(new Translate[StorageAction, O] {
      override def apply[X](kv: StorageAction[X]): Eff[O, X] = {
        writer.tell[O, L](log(kv)) *> kv.send[O]
      }
    })
  }

  def logKeys[I, O, U, A](eff: Eff[I, A])
                         (implicit ev: Member.Aux[StorageAction, I, U],
                          ev2: Member[StorageAction, O],
                          ev3: MemberIn[Writer[Vector[String], ?], O],
                          ev4: IntoPoly[I, O]): Eff[O, A] =
    logProgram(eff)(new (StorageAction ~> λ[P => Vector[String]]) {
      override def apply[X](fa: StorageAction[X]): Vector[String] = fa match {
        case StorageAction.Get(k) => Vector.empty[String] :+ k
        case StorageAction.Update(k, _) => Vector.empty[String] :+ k
        case StorageAction.Remove(_) => Vector.empty[String]
      }
    })

  def withLoggedKeys[R, U, A](prog: Eff[R, A])(implicit ev: Member.Aux[StorageAction, R, U]): Eff[R, (A, Vector[String])] = {
    type mem = Member.Aux[Writer[Vector[String], ?], Fx.prepend[Writer[Vector[String], ?], R], R]
    writer.runWriterFold[Fx.prepend[Writer[Vector[String], ?], R], R, Vector[String], A, Vector[String]](
      StorageProgram.logKeys[R, Fx.prepend[Writer[Vector[String], ?], R], U, A](prog)
    )(writer.MonoidFold[Vector[String]])(implicitly[mem])
  }

  def logActions[I, O, U, A](eff: Eff[I, A])
                            (implicit ev: Member.Aux[StorageAction, I, U],
                             ev2: Member[StorageAction, O],
                             ev3: MemberIn[Writer[Vector[String], ?], O],
                             ev4: IntoPoly[I, O]): Eff[O, A] = {
    logProgram(eff)(new (StorageAction ~> λ[P => Vector[String]]) {
      override def apply[X](kv: StorageAction[X]): Vector[String] = kv match {
        case StorageAction.Get(k) => Vector.empty[String] :+ s"Get($k)"
        case StorageAction.Update(k, v) => Vector.empty[String] :+ s"Update($k, $v)"
        case StorageAction.Remove(k) => Vector.empty[String] :+ s"Remove($k)"
      }
    })
  }

}

// Implementation for dom.ext.Storage values
sealed class DomStorage(underlying: SStorage) extends Storage[Task] {
  override def apply(key: String): Task[Option[String]] = Task.eval(underlying(key))

  override def update(key: String, data: String): Task[Unit] = Task.eval(underlying.update(key, data))

  override def remove(key: String): Task[Unit] = Task.eval(underlying.remove(key))
}

object DomStorage {
  case object Local extends DomStorage(LocalStorage)

  case object Session extends DomStorage(SessionStorage)
}

// Implementation for pure maps
object PureStorage extends Storage[State[Map[String, String], ?]] {
  override def apply(key: String): State[Map[String, String], Option[String]] =
    State.get.map(_.get(key))

  override def update(key: String, data: String): State[Map[String, String], Unit] =
    State.modify(_ + (key -> data))

  override def remove(key: String): State[Map[String, String], Unit] =
    State.modify(_ - key)
}

