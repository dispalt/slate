package qq

import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.Assertion
import qq.cc.{QQCompiler, QQRuntime, UpickleRuntime}
import qq.data.ConcreteFilter
import upickle.Js
import upickle.Js.Value

import scala.concurrent.Future

// data representation of a compiler test case
case class CompilerTest(input: Value, program: ConcreteFilter, expectedOutput: Value*)

object CompilerTest {

  import qq.data.QQDSL.fix._
  import org.scalatest.Matchers._

  def runTest[J](runtime: QQRuntime[J], qqCompilerTest: CompilerTest)
                (implicit sch: Scheduler): Future[Assertion] = qqCompilerTest match {
    case CompilerTest(input, filter, expectedOutput@_*) =>
      QQCompiler
        .compileFilter(UpickleRuntime, IndexedSeq.empty, filter)
        .fold[Task[Assertion]](
        err => Task.eval(fail("error occurred during compilation: \n" + err.toString)),
        program => program(Map.empty)(input).map { output => output shouldBe expectedOutput.toList })
        .runAsync
  }

  val selectKeyTests: List[CompilerTest] = {
    val dict = Js.Obj("present" -> Js.Num(1))
    List(
      CompilerTest(dict, selectKey("present"), Js.Num(1)),
      CompilerTest(dict, selectKey("absent"), Js.Null)
    )
  }

  val selectIndexTests: List[CompilerTest] = {
    val arr = Js.Arr(Js.Num(1), Js.Num(2))
    List(
      CompilerTest(arr, selectIndex(-3), Js.Null),
      CompilerTest(arr, selectIndex(-2), Js.Num(1)),
      CompilerTest(arr, selectIndex(-1), Js.Num(2)),
      CompilerTest(arr, selectIndex(0), Js.Num(1)),
      CompilerTest(arr, selectIndex(1), Js.Num(2)),
      CompilerTest(arr, selectIndex(2), Js.Null)
    )
  }

  val idTests: List[CompilerTest] = {
    val dict = Js.Obj("present" -> Js.Num(1))
    List(CompilerTest(dict, id, dict))
  }

  val selectRangeTests: List[CompilerTest] = {
    val arr = Js.Arr(Js.Num(1), Js.Num(2), Js.Num(3), Js.Num(4))
    List(
      CompilerTest(arr, selectRange(0, 0), Js.Arr()),
      CompilerTest(arr, selectRange(0, 1), Js.Arr(Js.Num(1))),
      CompilerTest(arr, selectRange(0, 2), Js.Arr(Js.Num(1), Js.Num(2))),
      CompilerTest(arr, selectRange(0, 3), Js.Arr(Js.Num(1), Js.Num(2), Js.Num(3))),
      CompilerTest(arr, selectRange(0, 4), Js.Arr(Js.Num(1), Js.Num(2), Js.Num(3), Js.Num(4))),
      CompilerTest(arr, selectRange(0, 5), Js.Arr(Js.Num(1), Js.Num(2), Js.Num(3), Js.Num(4))),
      CompilerTest(arr, selectRange(1, 5), Js.Arr(Js.Num(2), Js.Num(3), Js.Num(4)))
    )
  }

  val collectResultsTests: List[CompilerTest] = {
    List(
      CompilerTest(Js.Arr(Js.Num(1), Js.Num(2), Js.Num(3), Js.Num(4)), collectResults, Js.Num(1), Js.Num(2), Js.Num(3), Js.Num(4)),
      CompilerTest(Js.Obj("a" -> Js.Num(1), "b" -> Js.Str("c")), collectResults, Js.Num(1), Js.Str("c"))
    )
  }

  val fatStackTests: List[CompilerTest] = {
    // tail recursive builder, giving you (i * 2) compositions of id with f.
    @annotation.tailrec
    def fun(f: ConcreteFilter, i: Int): ConcreteFilter = if (i == 0) f else fun(id | f | id, i - 1)
    List(
      CompilerTest(Js.False, fun(id, 1000), Js.False)
    )
  }

  val variableTests: List[CompilerTest] =
    List(
      CompilerTest(Js.Str("input"), asBinding("hello", id, deref("hello")), Js.Str("input")),
      CompilerTest(Js.Str("input"), asBinding("hello", add(id, constString("hi")), add(constString("hey"), deref("hello"))), Js.Str("heyinputhi"))
    )

  val pathSetterTests: List[CompilerTest] =
    List(
      CompilerTest(Js.Num(2), setPath(Nil, constNumber(3)), Js.Num(3)),
      CompilerTest(
        Js.Arr(
          Js.Obj("key1" -> Js.Obj("key2" -> Js.Str("input1")), "out1" -> Js.Str("output1")),
          Js.Obj("key1" -> Js.Obj("key2" -> Js.Str("input2")), "out1" -> Js.Str("output2"))
        ),
        getPathS(collectResults) | setPath(List(selectKey("key1"), selectKey("key2")), getPathS(selectKey("out1"))),
        Js.Obj("key1" -> Js.Obj("key2" -> Js.Str("output1")), "out1" -> Js.Str("output1")),
        Js.Obj("key1" -> Js.Obj("key2" -> Js.Str("output2")), "out1" -> Js.Str("output2"))
      )
    )

  val pathModifierTests: List[CompilerTest] =
    List(
      CompilerTest(Js.Num(2), modifyPath(Nil, constNumber(3)), Js.Num(3)),
      CompilerTest(
        Js.Arr(
          Js.Obj("key1" -> Js.Obj("out1" -> Js.Str("output1"))),
          Js.Obj("key1" -> Js.Obj("out1" -> Js.Str("output2")))
        ),
        modifyPath(List(collectResults, selectKey("key1"), selectKey("out1")), add(id, constString("mod"))),
        Js.Obj("key1" -> Js.Obj("out1" -> Js.Str("output1mod"))),
        Js.Obj("key1" -> Js.Obj("out1" -> Js.Str("output2mod")))
      ),
      CompilerTest(
        Js.Arr(
          Js.Obj("key1" -> Js.Obj("out1" -> Js.Str("output1"))),
          Js.Obj("key1" -> Js.Obj("out1" -> Js.Str("output2")))
        ),
        modifyPath(List(collectResults, selectKey("key1")), getPathS(selectKey("out1"))),
        Js.Obj("key1" -> Js.Str("output1")),
        Js.Obj("key1" -> Js.Str("output2"))
      ),
      CompilerTest(
        Js.Arr(
          Js.Obj("out1" -> Js.Str("output1")),
          Js.Obj("out1" -> Js.Str("output2"))
        ),
        modifyPath(List(selectIndex(0)), getPathS(selectKey("out1"))),
        Js.Arr(
          Js.Str("output1"),
          Js.Obj("out1" -> Js.Str("output2"))
        )
      )
    )


}
