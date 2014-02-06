package monifu.rx

import scala.concurrent.duration.FiniteDuration
import monifu.concurrent.atomic.Atomic
import scala.annotation.tailrec

trait Observable[+A]  {
  def subscribe(observer: Observer[A]): Subscription

  def subscribe(f: A => Unit): Subscription = 
    subscribe(
      onNext = (elem: A) => f(elem),
      onError = (ex: Throwable) => throw ex,
      onCompleted = () => ()
    )

  def subscribe(onNext: A => Unit, onError: Throwable => Unit, onCompleted: () => Unit): Subscription = {
    val n = onNext; val e = onError; val c = onCompleted

    subscribe(new Observer[A] {
      def onNext(elem: A): Unit = n(elem)
      def onCompleted(): Unit = c()
      def onError(ex: Throwable): Unit = e(ex)
    })
  }

  def map[B](f: A => B): Observable[B] = 
    Observable(observer => subscribe(
      (elem: A) => observer.onNext(f(elem)),
      (ex: Throwable) => observer.onError(ex),
      () => observer.onCompleted()
    ))

  def flatMap[B](f: A => Observable[B]): Observable[B] = 
    Observable(observer => {
      val composite = CompositeSubscription()

      composite += subscribe(
        onError = observer.onError,
        onCompleted = observer.onCompleted,
        onNext = (elem: A) => 
          composite += f(elem).subscribe(observer)
      )

      composite
    })

  def filter(p: A => Boolean): Observable[A] =
    Observable(observer => subscribe(
      onError = observer.onError,
      onCompleted = observer.onCompleted,
      onNext = (elem: A) => 
        if (p(elem)) observer.onNext(elem)
    ))

  def subscribeOn(s: Scheduler): Observable[A] =
    Observable(o => s.scheduleR(_ => subscribe(o)))
  
  def observeOn(s: Scheduler): Observable[A] =
    Observable(observer => subscribe(
      onNext = elem => s.schedule(observer.onNext(elem)),
      onError = ex => s.schedule(observer.onError(ex)),
      onCompleted = () => s.schedule(observer.onCompleted())
    ))

  def take(nr: Int): Observable[A] = {
    require(nr > 0, "number of elements to take should be strictly positive")

    Observable(observer => subscribe(new Observer[A] {
        val count = Atomic(0)

        @tailrec
        def onNext(elem: A): Unit = {
          val currentCount = count.get

          if (currentCount < nr) {
            val newCount = currentCount + 1
            if (!count.compareAndSet(currentCount, newCount))
              onNext(elem)
            else {
              observer.onNext(elem)
              if (newCount == nr)
                observer.onCompleted()
            }
          }
        }

        def onCompleted(): Unit =
          observer.onCompleted()

        def onError(ex: Throwable): Unit =
          observer.onError(ex)
      })
    )
  }

  def takeWhile(p: A => Boolean): Observable[A] =
    Observable(observer => subscribe(new Observer[A] {
      val shouldContinue = Atomic(true)

      def onNext(elem: A): Unit =
        if (shouldContinue.get) {
          val update = p(elem)
          if (shouldContinue.compareAndSet(expect=true, update=update) && update)
            observer.onNext(elem)
          else if (!update)
            observer.onCompleted()
        }

      def onCompleted(): Unit =
        observer.onCompleted()

      def onError(ex: Throwable): Unit =
        observer.onError(ex)
    }))

}

object Observable {
  def apply[A](f: Observer[A] => Subscription): Observable[A] =
    new Observable[A] {
      def subscribe(observer: Observer[A]): Subscription = {
        val sub = MultiAssignmentSubscription()
        sub() = f(SafeObserver(observer, sub))
        sub
      }
    }

  def unit[A](elem: A): Observable[A] =
    Observable[A] { observer => Subscription {
      observer.onNext(elem)
      observer.onCompleted()
    }}

  def never: Observable[Nothing] =
    Observable { observer => Subscription {} }

  def error(ex: Throwable): Observable[Nothing] =
    Observable { observer => 
      observer.onError(ex)
      Subscription.empty
    }

  def interval(period: FiniteDuration)(implicit s: Scheduler): Observable[Long] =
    Observable { observer =>
      val counter = Atomic(0L)

      val sub = s.schedule(period, period) {
        val nr = counter.getAndIncrement()
        observer.onNext(nr)
      }

      BooleanSubscription {
        sub.unsubscribe()
      }
    }
}

