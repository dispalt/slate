package qq

import qq.TestUtil._
import qq.jsc.JSRuntime
import monix.execution.Scheduler.Implicits.global
import utest._
import scala.concurrent.Future

object JSRunnerTest extends utest.TestSuite with Asserts {
  override val tests = TestSuite {
    import RunnerTest._
    def runTest(test: RunnerTest): Future[Unit] =
      Runner
        .run(JSRuntime, test.program)(List(upickle.json.writeJs(test.input).asInstanceOf[AnyRef]))
        .runFuture
        .transform(
          { out => val js = out.map(upickle.json.readJs); js ===> test.expectedOutput.valueOr { throw _ } },
          { ex => ex ===> test.expectedOutput.swap.getOrElse(???); ex }
        )
        .fallbackTo(Future.successful(()))

    "identity" - runTest(identityProgram)
    "ensequenced filters" - runTest(ensequencedFilters)
    "enlisted filter" - runTest(enlistedFilters)
    "select key" - runTest(selectKeyProgram)
    "collect results" - runTest(collectResults)
    "enject filter" - runTest(enjectedFilters)
    "pipes" - runTest(pipes)
    "length" - runTest(length)
    "keys" - runTest(keys)
    "classifiers" -
      Future.traverse(List(arrays, strings, booleans, scalars, objects, iterables, nulls, numbers))(runTest)
    "add" - runTest(add)
    "maths" - runTest(maths)
    "bedmas" - runTest(bedmas)
    "map" - runTest(map)
    "multiply" - runTest(multiply)
    "add null exception" - runTest(addNullException)
    "silenced exception" - runTest(silencedException)
  }
}
