import recycle.*, managed.*

def sumN(n: Int): Seq[Int] = manage:
  val log   = use(Logger("log"))
  val meter = use(Metrics.logging(log))

  log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
  defer(log.printLine("'sumN' completed"))

  (0 to n).map: i =>
    meter("vector"):
      log.printLine(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        meter("sum")(acc + i)

Printer.expr(sumN(10))
