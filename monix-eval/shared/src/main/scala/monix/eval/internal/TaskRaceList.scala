/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.eval.internal

import monix.eval.{Callback, Task}
import monix.execution.Cancelable
import monix.execution.atomic.{Atomic, PaddingStrategy}
import monix.execution.cancelables.StackedCancelable

private[eval] object TaskRaceList {
  /**
    * Implementation for `Task.raceList`
    */
  def apply[A](tasks: TraversableOnce[Task[A]]): Task[A] =
    Task.Async(new Register(tasks), trampolineBefore = true, trampolineAfter = true)

  // Implementing Async's "start" via `ForkedStart` in order to signal
  // that this is a task that forks on evaluation.
  //
  // N.B. the contract is that the injected callback gets called after
  // a full async boundary!
  private final class Register[A](tasks: TraversableOnce[Task[A]])
    extends ForkedRegister[A] {

    def apply(context: Task.Context, callback: Callback[A]): Unit = {
      implicit val s = context.scheduler
      val conn = context.connection

      val isActive = Atomic.withPadding(true, PaddingStrategy.LeftRight128)
      val taskArray = tasks.toArray
      val cancelableArray = buildCancelableArray(taskArray.length)

      val composite = Cancelable.trampolined(cancelableArray)
      conn.push(composite)

      var index = 0
      while (index < taskArray.length) {
        val task = taskArray(index)
        val taskCancelable = cancelableArray(index)
        val taskContext = context.withConnection(taskCancelable)
        index += 1

        Task.unsafeStartEnsureAsync(task, taskContext, new Callback[A] {
          private def popAndCancelRest(): Unit = {
            conn.pop()
            var i = 0
            while (i < cancelableArray.length) {
              val ref = cancelableArray(i)
              if (ref ne taskCancelable) ref.cancel()
              i += 1
            }
          }

          def onSuccess(value: A): Unit =
            if (isActive.getAndSet(false)) {
              popAndCancelRest()
              callback.onSuccess(value)
            }

          def onError(ex: Throwable): Unit =
            if (isActive.getAndSet(false)) {
              popAndCancelRest()
              callback.onError(ex)
            } else {
              s.reportFailure(ex)
            }
        })
      }
    }
  }

  private def buildCancelableArray(n: Int): Array[StackedCancelable] = {
    val array = new Array[StackedCancelable](n)
    var i = 0
    while (i < n) { array(i) = StackedCancelable(); i += 1 }
    array
  }
}
