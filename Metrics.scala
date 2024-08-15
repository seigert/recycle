package recycle

import scala.collection.mutable
import scala.collection.SortedMap

import managed.Resource

trait Metrics:
  def apply[A](metric: String)(f: => A): A

  def get: SortedMap[String, Metrics.Metric]

  def clear(): Unit
end Metrics

object Metrics:
  final case class Metric(count: Long, mean: Double, variance: Double)

  def apply(): Metrics =
    InMemory(mutable.HashMap.empty)

  def logging(log: Logger): Metrics =
    Logging(Metrics(), log)
  def logging_uar(): Metrics = Using(Logger("metrics")): log =>
    Logging(Metrics(), log)

  def resource(): Resource[Metrics] =
    Resource(Metrics())(_.clear())

  def lazyLogging(): Resource[Metrics] =
    resource().flatMap: origin =>
      val log = Logger.resource("metrics")
      Resource(LazyLogging(origin, log))(_.clear())

  given Using.Releasable[Metrics] with
    def release(a: Metrics): Unit = a.clear()

  private[Metrics] final case class Data(count: Long, mean: Double, m2: Double):
    def update(value: Long): Data =
      val count  = this.count + 1
      val delta  = value.toDouble - this.mean
      val mean   = this.mean + delta / count
      val delta2 = value.toDouble - mean
      val m2     = this.m2 + delta * delta2

      Data(count, mean, m2)
    end update
  end Data

  private[Metrics] final class InMemory(
      private var data: mutable.HashMap[String, Metrics.Data]
  ) extends Metrics:
    def apply[A](metric: String)(f: => A): A =
      val start = System.nanoTime()
      try f
      finally
        val delta = System.nanoTime() - start
        data.updateWith(metric): opt =>
          Some(opt.getOrElse(Data(0, 0.0, 0.0)).update(delta))

    def get: SortedMap[String, Metric] =
      val builder = SortedMap.newBuilder[String, Metric]
      data.foreach: (name, data) =>
        if data.count > 1 then builder += (name -> Metric(data.count, data.mean, data.m2 / data.count))

      builder.result()

    def clear(): Unit = data.clear()
  end InMemory

  private[Metrics] final class Logging(
      underlying: Metrics,
      log: Logger,
  ) extends Metrics:
    def apply[A](metric: String)(f: => A): A = underlying(metric)(f)
    def get: SortedMap[String, Metric]       = underlying.get

    def clear(): Unit =
      log.printLine("collected metrics:")
      get.foreach: (name, metric) =>
        log.printLine(
          f"  $name%s -> ${metric.mean}%.2f ± ${math.sqrt(metric.variance)}%.2f (${metric.count}%d samples)"
        )
      underlying.clear()

  end Logging

  private[Metrics] final class LazyLogging(
      underlying: Metrics,
      log: Resource[Logger],
  ) extends Metrics:
    def apply[A](metric: String)(f: => A): A = underlying(metric)(f)
    def get: SortedMap[String, Metric]       = underlying.get

    def clear(): Unit = log.use: log =>
      log.printLine("collected metrics:")
      get.foreach: (name, metric) =>
        log.printLine(
          f"  $name%s -> ${metric.mean}%.2f ± ${math.sqrt(metric.variance)}%.2f (${metric.count}%d samples)"
        )
  end LazyLogging

end Metrics
