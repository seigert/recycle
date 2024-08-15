package recycle

import managed.Resource

final class Logger(name: String) extends AutoCloseable:
  @volatile private var released =
    println(s" -- Printer '$name' is acquired.")
    false

  def printLine(s: String): Unit =
    if released then println(s"$name: !!! WARN !!! Use of printer '$name' after release.")
    println(s"$name: $s")

  def close(): Unit =
    if !released then
      synchronized:
        if !released then
          println(s" -- Printer '$name' is released")
          released = true
end Logger

object Logger:
  def resource(name: String): Resource[Logger] =
    Resource(Logger(name))(_.close())
