package recycle

import scala.util.control.NonFatal

object Using:
  trait Releasable[-A]:
    def release(a: A): Unit

  given [A <: AutoCloseable]: Releasable[A] with
    def release(a: A): Unit = a.close()

  def apply[A: Releasable, B](a: A)(f: A => B): B =
    var toThrow: Throwable = null

    try f(a)
    catch
      case NonFatal(e) =>
        toThrow = e
        null.asInstanceOf[B]
    finally
      try summon[Releasable[A]].release(a)
      catch
        case NonFatal(e) =>
          if toThrow ne null then toThrow.addSuppressed(e)
          else toThrow = e

      if toThrow ne null then throw toThrow
    end try
  end apply

  def apply[A1: Releasable, A2: Releasable, B](a1: A1, a2: => A2)(f: (A1, A2) => B): B =
    apply(a1)(a1 => apply(a2)(a2 => f(a1, a2)))

  final class Manager private () extends AutoCloseable:
    private var closed  = false
    private var handles = List.empty[() => Unit]

    def apply[A: Releasable](a: A): a.type =
      if !closed then handles = (() => summon[Releasable[A]].release(a)) :: handles
      else throw new IllegalStateException("Manager has already been closed")
      a

    def close(): Unit =
      closed = true

      val toRelease          = handles
      var toThrow: Throwable = null

      handles = null
      toRelease.foreach: release =>
        try release()
        catch
          case NonFatal(e) =>
            if toThrow ne null then e.addSuppressed(toThrow)
            toThrow = e

      if toThrow ne null then throw toThrow
    end close

  end Manager

  object Manager:
    def apply[A](f: Manager => A): A = Using(new Manager())(f(_))

end Using
