import recycle.*, managed.*

def sum(x: Int, y: Int): Int = Logger
  .resource("log")
  .use: log =>
    log.printLine(s"will sum x = $x and y = $y")
    x + y

Printer.expr(sum(2, 2))

def sumN(n: Int): Seq[Int] =
  val resources = for
    log     <- Logger.resource("log")
    metrics <- Metrics.lazyLogging()
  yield (log, metrics)

  resources.use: (log, meter) =>
    log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
    (0 to n).map: i =>
      meter("vector"):
        log.printLine(s"will sum range [$i, $n]")
        (i to n).foldLeft(0): (acc, i) =>
          meter("sum")(acc + i)
end sumN

Printer.expr(sumN(10))

def sumN_acquire(n: Int): Seq[Int] = manage:
  val log   = acquire(Logger.resource("log"))
  val meter = acquire(Metrics.lazyLogging())

  log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
  (0 to n).map: i =>
    meter("vector"):
      log.printLine(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        meter("sum")(acc + i)
end sumN_acquire

Printer.expr(sumN_acquire(10))

def sumN_manage(n: Int): Seq[Int] = managing(
  Logger.resource("log"),
  Metrics.lazyLogging()
): (log, meter) =>
  log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
  (0 to n).map: i =>
    meter("vector"):
      log.printLine(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        meter("sum")(acc + i)
end sumN_manage

Printer.expr(sumN_manage(10))
