package qq
package data

import matryoshka.{Corecursive, Fix, Mu, Nu}

import scala.language.{higherKinds, implicitConversions}
import scalaz.\/

// smart constructors that make a FilterComponent node including its children
object QQDSL {

  class Dsl[T[_[_]] : Corecursive] {

    import qq.data.FilterComponent.embed

    implicit class extraOps(val f: T[FilterComponent]) {
      final def |(next: T[FilterComponent]): T[FilterComponent] = compose(f, next)

      final def +(next: T[FilterComponent]): T[FilterComponent] = add(f, next)

      final def -(next: T[FilterComponent]): T[FilterComponent] = subtract(f, next)

      final def *(next: T[FilterComponent]): T[FilterComponent] = multiply(f, next)

      final def /(next: T[FilterComponent]): T[FilterComponent] = divide(f, next)

      final def %(next: T[FilterComponent]): T[FilterComponent] = modulo(f, next)
    }

    implicit def embedStr(s: String): T[FilterComponent] = constString(s)

    implicit def embedInt(d: Int): T[FilterComponent] = constNumber(d)

    implicit def embedDouble(d: Double): T[FilterComponent] = constNumber(d)

    @inline final def define(name: String, params: List[String], body: T[FilterComponent]): Definition[T[FilterComponent]] =
      Definition[T[FilterComponent]](name, params, body)

    @inline final def id: T[FilterComponent] =
      embed[T](IdFilter())

    @inline final def compose(first: T[FilterComponent], second: T[FilterComponent]): T[FilterComponent] =
      embed[T](ComposeFilters(first, second))

    @inline final def silence(f: T[FilterComponent]): T[FilterComponent] =
      embed[T](SilenceExceptions(f))

    @inline final def enlist(f: T[FilterComponent]): T[FilterComponent] =
      embed[T](EnlistFilter(f))

    @inline final def collectResults: T[FilterComponent] =
      embed[T](CollectResults())

    @inline final def ensequence(first: T[FilterComponent], second: T[FilterComponent]): T[FilterComponent] =
      embed[T](EnsequenceFilters(first, second))

    @inline final def enject(obj: List[((String \/ T[FilterComponent]), T[FilterComponent])]): T[FilterComponent] =
      embed[T](EnjectFilters(obj))

    @inline final def call(name: String, params: List[T[FilterComponent]] = Nil): T[FilterComponent] =
      embed[T](CallFilter(name, params))

    @inline final def selectKey(key: String): T[FilterComponent] =
      embed[T](SelectKey(key))

    @inline final def selectIndex(index: Int): T[FilterComponent] =
      embed[T](SelectIndex(index))

    @inline final def selectRange(start: Int, end: Int): T[FilterComponent] =
      embed[T](SelectRange(start, end))

    @inline final def add(first: T[FilterComponent], second: T[FilterComponent]): T[FilterComponent] =
      embed[T](FilterMath(first, second, Add))

    @inline final def subtract(first: T[FilterComponent], second: T[FilterComponent]): T[FilterComponent] =
      embed[T](FilterMath(first, second, Subtract))

    @inline final def multiply(first: T[FilterComponent], second: T[FilterComponent]): T[FilterComponent] =
      embed[T](FilterMath(first, second, Multiply))

    @inline final def divide(first: T[FilterComponent], second: T[FilterComponent]): T[FilterComponent] =
      embed[T](FilterMath(first, second, Divide))

    @inline final def modulo(first: T[FilterComponent], second: T[FilterComponent]): T[FilterComponent] =
      embed[T](FilterMath(first, second, Modulo))

    @inline final def constNumber(value: Double): T[FilterComponent] =
      embed[T](ConstNumber(value))

    @inline final def constString(value: String): T[FilterComponent] =
      embed[T](ConstString(value))

    @inline final def letAsBinding(name: String, as: T[FilterComponent], in: T[FilterComponent]): T[FilterComponent] =
      embed[T](LetAsBinding[T[FilterComponent]](name, as, in))

    @inline final def deref(name: String): T[FilterComponent] =
      embed[T](Dereference[T[FilterComponent]](name))
  }

  final def dsl[T[_[_]] : Corecursive]: Dsl[T] = new Dsl[T]

  final val mu: Dsl[Mu] = dsl[Mu]
  final val nu: Dsl[Nu] = dsl[Nu]
  final val fix: Dsl[Fix] = dsl[Fix]

}