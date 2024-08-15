package recycle
package managed

import scala.util.control.NonFatal

final class Manager private[managed] ():
  private var closed  = false
  private var handles = List.empty[() => Unit]

  private[managed] def handle(f: () => Unit): Unit =
    if !closed then handles = f :: handles
    else throw new IllegalStateException("Managed instance has already been closed")

  private[managed] def close(): Unit =
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

def manage[A](f: Manager ?=> A): A =
  val manager            = Manager()
  var toThrow: Throwable = null

  try f(using manager)
  catch
    case NonFatal(e) =>
      toThrow = e
      null.asInstanceOf[A]
  finally
    try manager.close()
    catch
      case NonFatal(e) =>
        if toThrow ne null then toThrow.addSuppressed(e)
        else toThrow = e

    if toThrow ne null then throw toThrow
  end try
end manage

def defer(f: => Unit)(using M: Manager): Unit = M.handle(() => f)
def use[A](a: A)(using M: Manager, R: Using.Releasable[A]): A =
  defer(R.release(a))
  a

def acquire[A](r: Resource[A])(using M: Manager): A =
  val (a, release) = r.allocate
  defer(release())
  a

type ResourceParams[X <: NonEmptyTuple] <: NonEmptyTuple = X match
  case Resource[a] *: EmptyTuple => a *: EmptyTuple
  case Resource[a] *: tail       => a *: ResourceParams[tail]

def managing[X <: NonEmptyTuple, A](rs: X)(using ev: Tuple.Union[X] <:< Resource[?])(
    f: ResourceParams[X] => Manager ?=> A
): A = manage:
  def loop(rest: NonEmptyTuple, acc: Tuple): NonEmptyTuple = (rest: @unchecked) match
    case (r: Resource[a]) *: EmptyTuple            => acc :* acquire(r)
    case (r: Resource[a]) *: (tail: NonEmptyTuple) => loop(tail, acc :* acquire(r))

  f(loop(rs, EmptyTuple).asInstanceOf[ResourceParams[X]])
