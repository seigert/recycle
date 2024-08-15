package recycle
package managed

import scala.util.control.NonFatal

trait Resource[A]:
  def allocate: (A, () => Unit)

  def use[B](f: A => B): B =
    val (a, release)       = allocate
    var toThrow: Throwable = null

    try f(a)
    catch
      case NonFatal(e) =>
        toThrow = e
        null.asInstanceOf[B]
    finally
      try release()
      catch
        case NonFatal(e) =>
          if toThrow ne null then toThrow.addSuppressed(e)
          else toThrow = e

      if toThrow ne null then throw toThrow
    end try
  end use

  def map[B](f: A => B): Resource[B]               = Resource.Map(this, f)
  def flatMap[B](f: A => Resource[B]): Resource[B] = Resource.Bind(this, f)
end Resource

object Resource:
  def apply[A](acquire: => A)(release: A => Unit): Resource[A] = new Resource[A]:
    def allocate: (A, () => Unit) =
      val a = acquire
      (a, () => release(a))

  private[Resource] final class Map[A, B](underlying: Resource[A], f: A => B) extends Resource[B]:
    def allocate: (B, () => Unit) =
      val (a, release) = underlying.allocate
      (f(a), release)

  private[Resource] final class Bind[A, B](underlying: Resource[A], f: A => Resource[B]) extends Resource[B]:
    def allocate: (B, () => Unit) =
      val (a, releaseA) = underlying.allocate

      try
        val (b, releaseB) = f(a).allocate

        val releaseBoth = () =>
          var toThrow: Throwable = null

          try releaseB()
          catch case NonFatal(e) => toThrow = e
          finally
            try releaseA()
            catch
              case NonFatal(e) =>
                if toThrow ne null then e.addSuppressed(toThrow)
                toThrow = e
          end try

          if toThrow ne null then throw toThrow

        (b, releaseBoth)
      catch
        case NonFatal(e) =>
          try releaseA()
          catch
            case NonFatal(e2) =>
              e.addSuppressed(e2)

          throw e
      end try
    end allocate
  end Bind
end Resource
