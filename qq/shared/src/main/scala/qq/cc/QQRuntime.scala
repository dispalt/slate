package qq
package cc

import monix.scalaz._

import scala.language.higherKinds
import scalaz.\/

import monix.eval.{Coeval, Task}
import monix.scalaz._
import qq.data._
import qq.util._

import scala.collection.immutable.Nil
import scalaz.std.list._
import scalaz.std.map._
import scalaz.syntax.tag._
import scalaz.syntax.apply._
import scalaz.syntax.traverse._
import scalaz.{-\/, \/, \/-}

object QQRuntime {

  val taskOfListOfNull: Task[List[JSON]] = Task.now(List(JSON.Null))
  val emptyArray: JSON.Arr = JSON.Arr()

  @inline final def makePathComponentGetter(component: PathComponent): CompiledProgram = component match {
    case CollectResults => QQRuntime.collectResults
    case SelectKey(key) => QQRuntime.selectKey(key)
    case SelectIndex(index) => QQRuntime.selectIndex(index)
    case SelectRange(start, end) => QQRuntime.selectRange(start, end)
  }

  def modifyPath(component: PathComponent)(f: CompiledProgram): CompiledProgram = component match {
    case CollectResults => {
      case arr: JSON.Arr => arr.value.traverseM[Task, JSON](f)
      case v => Task.raiseError(QQRuntimeException(TypeError(
        "collect results from", "array" -> v)))
    }
    case SelectKey(key) => {
      case obj: JSON.Obj =>
        val asMap = obj.toMap
        asMap.value.get(key).fold(taskOfListOfNull)(f).map(_.map(v => asMap.copy(value = asMap.value + (key -> v))))
      case v => Task.raiseError(QQRuntimeException(TypeError(
        "select key \"" + key + "\" in", "object" -> v)))
    }
    case SelectIndex(index) => {
      case arr: JSON.Arr =>
        if (arr.value.length <= index) {
          taskOfListOfNull
        } else {
          f(arr.value(index)).map {
            _.map { v =>
              JSON.Arr(arr.value.updated(index, v))
            }
          }
        }
      case v =>
        Task.raiseError(QQRuntimeException(TypeError(
          "select index " + index + " in", "array" -> v)))
    }
    case SelectRange(start, end) => ???
  }

  def setPath(components: List[PathComponent], biggerStructure: JSON, smallerStructure: JSON): Task[List[JSON]] = components match {
    case (component :: rest) => component match {
      case CollectResults => biggerStructure match {
        case arr: JSON.Arr => arr.value.traverseM(setPath(rest, _, smallerStructure))
        case v => Task.raiseError(QQRuntimeException(TypeError(
          "collect results from", "array" -> v)))
      }
      case SelectKey(key) => biggerStructure match {
        case obj: JSON.Obj =>
          val asMap = obj.toMap
          asMap.value.get(key).fold(taskOfListOfNull)(
            setPath(rest, _, smallerStructure).map(_.map(nv => asMap.copy(value = asMap.value.updated(key, nv))))
          )
        case v => Task.raiseError(QQRuntimeException(TypeError(
          "select key \"" + key + "\" from ", "array" -> v)))
      }
      case SelectIndex(index) => biggerStructure match {
        case arr: JSON.Arr =>
          if (arr.value.length <= index) {
            Task.raiseError(???)
          } else {
            setPath(rest, arr.value(index), smallerStructure).map(_.map(v => JSON.Arr(arr.value.updated(index, v))))
          }
        case v =>
          Task.raiseError(QQRuntimeException(TypeError(
            "select index " + index + " in", "array" -> v)))
      }
      case SelectRange(start, end) => ???
    }
    case Nil => Task.now(List(smallerStructure))
  }

  def constNumber(num: Double): CompiledFilter =
    CompiledFilter.const(JSON.Num(num))

  def constString(str: String): CompiledFilter =
    CompiledFilter.const(JSON.Str(str))

  def constBoolean(bool: Boolean): CompiledFilter =
    CompiledFilter.const(if (bool) JSON.True else JSON.False)

  def addJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) =>
      Coeval.now(JSON.Num(f + s))
    case (JSON.Str(f), JSON.Str(s)) =>
      Coeval.now(JSON.Str(f + s))
    case (f: JSON.Arr, s: JSON.Arr) =>
      Coeval.now(JSON.Arr(f.value ++ s.value))
    case (f: JSON.Obj, s: JSON.Obj) =>
      Coeval.now(JSON.ObjMap(f.toMap.value ++ s.toMap.value))
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError(
        "add",
        "number | string | array | object" -> f,
        "number | string | array | object" -> s)))
  }

  def subtractJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) =>
      Coeval.now(JSON.Num(f - s))
    case (f: JSON.Arr, s: JSON.Arr) =>
      Coeval.now(JSON.Arr(f.value.filter(!s.value.contains(_))))
    case (f: JSON.Obj, s: JSON.Obj) =>
      val contents: Map[String, JSON] = f.toMap.value -- s.map[String, Set[String]](_._1)(collection.breakOut)
      Coeval.now(JSON.ObjMap(contents))
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError(
        "subtract",
        "number | array | object" -> f,
        "number | array | object" -> s)))
  }

  def multiplyJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) => Coeval.now(JSON.Num(f * s))
    case (JSON.Str(f), JSON.Num(s)) => Coeval.now(if (s == 0) JSON.Null else JSON.Str(f * s.toInt))
    case (f: JSON.Obj, s: JSON.Obj) =>
      val firstMapCoeval = f.toMap.value.mapValues(Coeval.now)
      val secondMapCoeval = s.toMap.value.mapValues(Coeval.now)
      mapInstance[String].sequence[Coeval, JSON](
        unionWith(firstMapCoeval, secondMapCoeval)(
          (f, s) => (f |@| s) (addJsValues).flatten
        )
      ).map(JSON.ObjMap)
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError(
        "multiply",
        "number | string | object" -> f,
        "number | object" -> s)))
  }

  def divideJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) => Coeval.now(JSON.Num(f / s))
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError("divide", "number" -> f, "number" -> s)))
  }

  def moduloJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) => Coeval.now(JSON.Num(f % s))
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError("modulo", "number" -> f, "number" -> s)))
  }

  def equalJsValues(first: JSON, second: JSON): Coeval[JSON] =
    Coeval.now(if (first == second) JSON.True else JSON.False)

  def lteJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) =>
      Coeval.now(if (f <= s) JSON.True else JSON.False)
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError("lte", "number" -> f, "number" -> s)))
  }

  def gteJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) =>
      Coeval.now(if (f >= s) JSON.True else JSON.False)
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError("gte", "number" -> f, "number" -> s)))
  }

  def lessThanJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) =>
      Coeval.now(if (f < s) JSON.True else JSON.False)
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError("lessThan", "number" -> f, "number" -> s)))
  }

  def greaterThanJsValues(first: JSON, second: JSON): Coeval[JSON] = (first, second) match {
    case (JSON.Num(f), JSON.Num(s)) =>
      Coeval.now(if (f > s) JSON.True else JSON.False)
    case (f, s) =>
      Coeval.raiseError(QQRuntimeException(TypeError("greaterThan", "number" -> f, "number" -> s)))
  }

  def not(v: JSON): Coeval[JSON] = v match {
    case JSON.True => Coeval.now(JSON.False)
    case JSON.False => Coeval.now(JSON.True)
    case k => Coeval.raiseError(QQRuntimeException(TypeError("not", "boolean" -> k)))
  }

  def enlistFilter(filter: CompiledFilter): CompiledFilter =
    (bindings: VarBindings) =>
      (jsv: JSON) =>
        for {
          results <- filter(bindings)(jsv)
        } yield JSON.Arr(results) :: Nil

  def selectKey(key: String): CompiledProgram = {
    case f: JSON.Obj =>
      f.toMap.value.get(key) match {
        case None => taskOfListOfNull
        case Some(v) => Task.now(v :: Nil)
      }
    case v =>
      Task.raiseError(QQRuntimeException(TypeError("select key " + key, "object" -> v)))
  }

  def selectIndex(index: Int): CompiledProgram = {
    case f: JSON.Arr =>
      val seq = f.value
      if (index >= -seq.length) {
        if (index >= 0 && index < seq.length) {
          Task.now(seq(index) :: Nil)
        } else if (index < 0) {
          Task.now(seq(seq.length + index) :: Nil)
        } else {
          taskOfListOfNull
        }
      } else {
        taskOfListOfNull
      }
    case v =>
      Task.raiseError(QQRuntimeException(TypeError("select index " + index.toString, "array" -> v)))
  }

  def selectRange(start: Int, end: Int): CompiledProgram = {
    case f: JSON.Arr =>
      val seq = f.value
      if (start < end && start < seq.length) {
        Task.now(JSON.Arr(seq.slice(start, end)) :: Nil)
      } else {
        Task.now(emptyArray :: Nil)
      }
    case v =>
      Task.raiseError(QQRuntimeException(TypeError(
        "select range " + start + ":" + end, "array" -> v)))
  }

  def collectResults: CompiledProgram = {
    case arr: JSON.Arr =>
      Task.now(arr.value)
    case dict: JSON.Obj =>
      Task.now(dict.map(_._2)(collection.breakOut))
    case v =>
      Task.raiseError(QQRuntimeException(TypeError(
        "flatten", "array" -> v)))
  }

  def enjectFilter(obj: List[(String \/ CompiledFilter, CompiledFilter)]): CompiledFilter = {
    if (obj.isEmpty) {
      CompiledFilter.func(_ => Task.now(JSON.Obj() :: Nil))
    } else {
      bindings: VarBindings =>
        jsv: JSON =>
          for {
            kvPairs <- obj.traverse[TaskParallel, List[List[(String, JSON)]]] {
              case (\/-(filterKey), filterValue) =>
                (for {
                  keyResults <- filterKey(bindings)(jsv)
                  valueResults <- filterValue(bindings)(jsv)
                  keyValuePairs <- keyResults.traverse[Task, List[(String, JSON)]] {
                    case JSON.Str(keyString) =>
                      Task.now(valueResults.map(keyString -> _))
                    case k =>
                      Task.raiseError(QQRuntimeException(TypeError(
                        "use as key", "string" -> k)))
                  }
                } yield keyValuePairs).parallel
              case (-\/(filterName), filterValue) =>
                filterValue(bindings)(jsv).map(_.map(filterName -> _) :: Nil).parallel
            }.unwrap
            kvPairsProducts = kvPairs.map(_.flatten).unconsFold(Nil, foldWithPrefixes[(String, JSON)](_, _: _*))
          } yield kvPairsProducts.map(JSON.ObjList)
    }
  }

  def printType(value: JSON): String = value match {
    case _: JSON.Num => "number"
    case JSON.True | JSON.False => "boolean"
    case _: JSON.Arr => "array"
    case _: JSON.Obj => "object"
    case JSON.Null => "null"
    case _: JSON.Str => "string"
  }

  def print(value: JSON): String = JSON.render(value)
}
