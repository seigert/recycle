package recycle
package captured

import scala.language.experimental.captureChecking
import scala.util.control.NonFatal
import scala.annotation.capability

trait Resource[A]:
  def allocate: (A, () -> Unit)

  def use[B](f: A^ => B): B =
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
end Resource

object Resource:
  def apply[A](acquire: -> A)(release: A -> Unit): Resource[A] = new Resource[A]:
    def allocate: (A, () -> Unit) =
      val a = acquire
      (a, () => release(a))

@capability
final class Manager private[captured] ():
  self =>
  private var closed  = false
  private var handles: List[() ->{self} Unit]^{self} = Nil

  private[captured] def handle(f: () ->{self} Unit): Unit =
    if !closed then handles = f :: handles
    else throw new IllegalStateException("Managed instance has already been closed")

  private[captured] def close(): Unit =
    closed = true

    var toRelease          = handles
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

def defer(using M: Manager)(f: ->{M} Unit): Unit = M.handle(() => f)
def use[A](a: A)(using M: Manager, R: Using.Releasable[A]): A =
  defer(R.release(a))
  a
def acquire[A](r: Resource[A])(using M: Manager): A^{M} =
  val (a, release) = r.allocate
  defer(release())
  a

object Main {
  def logger(name: String): Resource[Logger] =
    Resource(Logger(name))(_.close())

  def sum(x: Int, y: Int): Int = logger("log").use: log =>
    log.printLine(s"will sum x = $x and y = $y")
    x + y

  // Won't compile
  // def sum_error(x: Int, y: Int): Int =
  //   val print = logger("log").use: log =>
  //     (s: String) => log.printLine(s)
  //
  //   print(s"will sum x = $x and y = $y")
  //   x + y

  def sumN(n: Int): Seq[Int] = manage:
    val log = acquire(logger("log"))
    log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
    (0 to n).map: i =>
        log.printLine(s"will sum range [$i, $n]")
        (i to n).foldLeft(0): (acc, i) =>
          acc + i

  def sumN_delay(n: Int): Seq[Int] = manage:
    val print =
      val log = acquire(logger("log"))
      (s: String) => log.printLine(s)

    print(s"will sum $n ranges [i..$n] where i in [0..$n]")
    (0 to n).map: i =>
        print(s"will sum range [$i, $n]")
        (i to n).foldLeft(0): (acc, i) =>
          acc + i

  // Won't compile
  // def sumN_error(n: Int): Seq[Int] =
  //   val print = manage:
  //     val log = acquire(logger("log"))
  //     (s: String) => log.printLine(s)
  //
  //   print(s"will sum $n ranges [i..$n] where i in [0..$n]")
  //   (0 to n).map: i =>
  //       print(s"will sum range [$i, $n]")
  //       (i to n).foldLeft(0): (acc, i) =>
  //         acc + i

  def main(args: Array[String]): Unit =
    Printer.expr(sum(2, 2))
    // Printer.expr(sum_error(2, 2))

    Printer.expr(sumN(3))
    Printer.expr(sumN_delay(3))
    // Printer.expr(sumN_error(3))

}
