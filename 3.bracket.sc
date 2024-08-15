import recycle.*

import scala.util.control.NonFatal

def bracket[A, B](acquire: => A)(use: A => B)(release: A => Unit): B =
  val a                  = acquire
  var toThrow: Throwable = null

  try use(a)
  catch
    case NonFatal(e) =>
      toThrow = e
      null.asInstanceOf[B]
  finally
    try release(a)
    catch
      case NonFatal(e) =>
        if toThrow ne null then toThrow.addSuppressed(e)
        else toThrow = e

    if toThrow ne null then throw toThrow
  end try
end bracket

def sum(x: Int, y: Int): Int =
  bracket:
    Logger("log")
  .apply: log =>
    log.printLine(s"will sum x = $x and y = $y")
    x + y
  .apply: log =>
    log.close()

Printer.expr(sum(2, 2))

def sumN(n: Int): Seq[Int] =
  bracket:
    val log   = Logger("log")
    val meter = Metrics.logging(log)
    (log, meter)
  .apply: (log, meter) =>
    log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
    (0 to n).map: i =>
      meter("vector"):
        log.printLine(s"will sum range [$i, $n]")
        (i to n).foldLeft(0): (acc, i) =>
          meter("sum")(acc + i)
  .apply: (log, meter) =>
    meter.clear()
    log.close()

Printer.expr(sumN(10))
