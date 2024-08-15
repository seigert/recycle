import recycle.*, managed.*

def sum(x: Int, y: Int): Int =
  val print = Logger
    .resource("log")
    .use: log =>
      (s: String) => log.printLine(s)

  print(s"will sum x = $x and y = $y")
  x + y
end sum

Printer.expr(sum(2, 2))

def sumN(n: Int): Seq[Int] = manage:
  val log = acquire(Logger.resource("log"))
  val (seqMetrics, sumMetrics) = Metrics
    .lazyLogging()
    .use: metrics =>
      (metrics[Int]("vector"), metrics[Int]("sum"))

  log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
  (0 to n).map: i =>
    seqMetrics:
      log.printLine(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        sumMetrics:
          acc + i
end sumN

Printer.expr(sumN(10))
