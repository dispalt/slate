package qq.cc

import cats.data.{Validated, Xor}
import cats.implicits._
import org.atnos.eff._
import Eff._
import cats.{Semigroup, Traverse}

trait ValidatedInterpretation extends ValidatedTypes {

  import interpret.{Recurse, interpret1}

  /**
    * Run an error effect.
    *
    * Accumulate errors in an applicative fashion.
    */
  def runErrorParallel[R, U, E: Semigroup, A](r: Eff[R, A])(implicit m: Member.Aux[E Validated ?, R, U]): Eff[U, E Validated A] = {
    val recurse = new Recurse[E Validated ?, U, E Validated A] {
      def apply[X](m: E Validated X): X Xor Eff[U, E Validated A] =
        m match {
          case i@Validated.Invalid(e) =>
            EffMonad[U].pure(i: Validated[E, A]).right[X]

          case Validated.Valid(a) =>
            a.left
        }


      def applicative[X, T[_] : Traverse](ms: T[E Validated X]): T[X] Xor (E Validated T[X]) =
        ms.sequence[E Validated ?, X].right
    }

    interpret1[R, U, E Validated ?, A, E Validated A](_.valid)(recurse)(r)
  }

}

trait ValidatedCreation extends ValidatedTypes {
  def valid[R :_validated, E, A](a: => A): Eff[R, A] =
    send[Validated[E, ?], R, A](a.valid)
  def invalid[R :_validated, E, A](a: => E): Eff[R, A] =
    send[Validated[E, ?], R, A](a.invalid)
}

trait ValidatedTypes[E] {
  type _Validated[R] = Validated[E, ?] <= R
  type _validated[R] = Validated[E, ?] |= R
}
