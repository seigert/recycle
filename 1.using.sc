import recycle.*, managed.*

def sum(x: Int, y: Int): Int = Using(Logger("log")): log =>
  log.printLine(s"will sum x = $x and y = $y")
  x + y

Printer.expr(sum(2, 2))

def sumN_twice(n: Int): Seq[Int] =
  Using(Logger("log")): log =>
    Using(Metrics()): meter =>
      log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
      (0 to n).map: i =>
        meter("vector"):
          log.printLine(s"will sum range [$i, $n]")
          (i to n).foldLeft(0): (acc, i) =>
            meter("sum")(acc + i)

Printer.expr(sumN_twice(10))

def sumN(n: Int): Seq[Int] = Using(Logger("log"), Metrics()): (log, meter) =>
  log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")

  val result = (0 to n).map: i =>
    meter("vector"):
      log.printLine(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        meter("sum")(acc + i)

  log.printLine(s"metrics:")
  meter.get.foreach: (name, metric) =>
    log.printLine(s"  $name -> ${metric.mean} ± ${math.sqrt(metric.variance)} (${metric.count} samples)")

  result

Printer.expr(sumN(10))

def sumN_logging_uar(n: Int): Seq[Int] = Using(Logger("log"), Metrics.logging_uar()): (log, meter) =>
  log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
  (0 to n).map: i =>
    meter("vector"):
      log.printLine(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        meter("sum")(acc + i)

Printer.expr(sumN_logging_uar(10))

def sumN_logging_using(n: Int): Seq[Int] =
  Using(Logger("log")): log =>
    Using(Metrics.logging(log)): meter =>
      log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
      (0 to n).map: i =>
        meter("vector"):
          log.printLine(s"will sum range [$i, $n]")
          (i to n).foldLeft(0): (acc, i) =>
            meter("sum")(acc + i)

Printer.expr(sumN_logging_using(10))

def sumN_manager(n: Int): Seq[Int] = Using.Manager: use =>
  val log   = use(Logger("log"))
  val meter = use(Metrics.logging(log))
  log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
  (0 to n).map: i =>
    meter("vector"):
      log.printLine(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        meter("sum")(acc + i)

Printer.expr(sumN_manager(10))
