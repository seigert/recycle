# It's a Cycle of Life

Привет! В этот раз хочется поговорить о "жизненном цикле" объектов в
программах на Scala.

## "Жизненный цикл" -- что это?!

Для начала давайте определимся с тем, что именно я подразумеваю под словами
"жизненный цикл" переменной в Scala в частности или в JVM вообще?

Самый простой случай -- это инициализация переменной на стеке выполняющегося метода
(желательно примитивного типа или объекта, попавшего под [escape analysis][EA]).

```scala
val i = 1
```

В данном отрывке код все очень просто: сначала поместили значение переменной на стек,
потом сняли с вершины стека при использовании и все.

Следующий случай -- создание экземпляра класса в куче:

```scala
val o = new Object
```

Сильно сложнее не стало: выделяется память под объект, происходит его инициализация в
конструкторе, потом его использование, потом его приберет сборщик мусора. Когда?
Никто не знает ответа на этот вопрос, как никто не знает и ответа на вопрос когда будут
(и будут ли) вызваны методы [`Cleaner.Cleanable#clean()`] и [`Object#finalize()`].

Третий случай, инициализация чего-то похожего на потоки выполнения или ввода/вывода и
получение данных из них:

```scala
val t = new Thread(...)
t.start()
```

Теперь, помимо инициализации объекта в конструкторе появляется еще и шаг "запуска"
выполнения потока, а также вопрос что делать с потоком по его выполнению: нужен ли нам
полученный результат, как его получить, да и завершится ли этот поток вообще и когда?

Четвертая ситуация: подключение к "стороннему" сервису, например, базе данных:

```scala
val session = DbSession(...).connect()
```

В данном случае помимо инициализации объекта и факта подключения у нас появляется еще и
внутреннее состояние самого подключения: могут произойти ошибки во время взаимодействия,
потребуется переподключение, по окончании использования важно завершить сессию и т.д.

Пятый, самый сложный случай: "транзакции" -- последовательности действий внутри сессий взаимодействия
со сторонними сервисами:

```scala
val rx = session.beginTransaction()
```

Помимо того, что у транзакции есть свое состояние (ошибка, откат, подтверждение),
нам еще нужно учитывать текущее состояние "внешнего" подключения к сервису. То есть,
в случае переподключения к сервису, нужно "переповторить" и все 'in-flight' транзакции,
выполнявшиеся в момент переподключения.

[EA]: https://blogs.oracle.com/javamagazine/post/escape-analysis-in-the-hotspot-jit-compiler
[`Object#finalize()`]: https://docs.oracle.com/javase/9/docs/api/java/lang/Object.html#finalize--
[`Cleaner.Cleanable#clean()`]: https://docs.oracle.com/javase/9/docs/api/java/lang/ref/Cleaner.Cleanable.html#clean--

## `try-with-resources`

Для того, чтобы дать пользователю какой-то стандартный механизм управления
по крайней мере "освобождением" объектов в далеком 2011 году в Java 7 появился механизм
`try-with-resources`.

Он весьма прост: пользователь может инициализировать переменную любого класса,
наследующего интерфейс [`AutoClosable`] вначале блока `try(...)`:

```java
var file = File.createTempFile("rcl-", ".tmp");

try(
    var fw = new FileWriter(file);
    var bw = new BufferedWriter(fw);
) {
    bw.write("Hello World!");
}
```

Java гарантирует нам, что для всех инициализированных таким образом переменных метод
`.close()` будет вызван в порядке, обратном порядку инициализации, по завершению блока
`try`, но до выполнения возможных блоков `catch` или `finally`.

Звучит неплохо, однако остаются несколько моментов:

1. Внешне никак не отличить, почему `var fw = new FileWriter(file)` можно написать
   внутри `try(...)`, а `var file = new File(..)` -- нельзя;
2. Инициализировав `var fw = new FileWriter(file)` вне блока мы не получим никакого
   предупреждения (или ошибки) от компилятора -- это совершенно законно.
   Но и вызова `.close()` не случится;
3. `AutoClosable` -- интерфейс, и мы никак не можем добавить его постфактум к уже
   существующим классам, кроме как создав "делегата";
4. Данный механизм работает только для "освобождения" ресурсов, вся инициализация
   должна быть сосредоточена в конструкторе.

[`AutoClosable`]: https://docs.oracle.com/javase/9/docs/api/java/lang/AutoCloseable.html

## `scala.util.Using`

Долгое время Scala не могла похвастать наличием даже такого механизма управления
жизненным циклом, однако в 2019 году, вместе с выходом Scala 2.13 там появился
"механизм" [`scala.util.Using`].

Не будем приводить тут его исходный код, вместо этого давайте попробуем сообразить,
как он может выглядеть в переложении на Scala 3:

```scala
object Using:
  trait Releasable[-A]:
    def release(a: A): Unit

  given [A <: AutoCloseable]: Releasable[A] with
    def release(a: A): Unit = a.close()

  def apply[A: Releasable, B](a: A)(f: A => B): B =
    var toThrow: Throwable = null

    try f(a)
    catch
      case NonFatal(e) =>
        toThrow = e
        null.asInstanceOf[B]
    finally
      try summon[Releasable[A]].release(a)
      catch
        case NonFatal(e) =>
          if toThrow ne null then toThrow.addSuppressed(e)
          else toThrow = e

      if toThrow ne null then throw toThrow
    end try
  end apply
```

Внутри объекта [`Using`] объявлены:

- типаж `Releasable[A]`, содержащий метод def release(a: A): Unit, освобождающий
  вса ресурсы, связанные с объектом типа `A`;
- `givens` (_"данность"_ `¯\_(ツ)_/¯`, он же `implicit`, оно же неявное значение)
  наличия экземпляра `Releasable` для любого потомка `AutoClosable`
- метод `apply`, который для любого типа `A: Releasable` и замыкания `f: A => B`
  гарантирует, что после выполнения `f` все ресурсы переданного экземпляра `a: A`
  будут освобождены вне зависимости от статуса выполнения `f` (успешно,
  с ошибкой и т.д.).

Предложенная реализация `apply` несколько наивна в деле обработки исключений,
однако в целом справляется со своими обязанностями: мы перехватываем все
`NonFatal` исключения, который могут произойти в процессе выполнения `f(a)`,
освобождаем связанные с `a` ресурсы и, дополнительно, также обрабатываем
`NonFatal` ошибки, которые могут произойти в процессе освобождения, добавляя их
по необходимости к уже существующим через механизм [`.addSuppressed`].

[`scala.util.Using`]: https://www.scala-lang.org/api/2.13.6/scala/util/Using$.html
[`Using`]: Using.scala
[`.addSuppressed`]: https://docs.oracle.com/javase/9/docs/api/java/lang/Throwable.html#addSuppressed-java.lang.Throwable-

## `Logger` & `Metrics`

Для иллюстрации предлагаемых в данной статье механизмов управления ресурсами
мы будем использовать два простых интерфейса:

```scala
final class Logger(name: String) extends AutoCloseable:
  def printLine(s: String): Unit = ???
  def close(): Unit = ???
```

[`Logger`] представляет нам метод `.printLine(..)`, который печает переданную
строку в консоль (вместе с собственным именем, `s"$name: $s"`) и умеет
"ругаться", если `.printLine(..)` был вызван после вызова `.close()`.
Кроме того, он наследует `AutoClosable`, а значит для него есть
`given Releasable[Logger]`.

```scala
trait Metrics:
  def apply[A](metric: String)(f: => A): A
  def get: SortedMap[String, Metrics.Metric]
  def clear(): Unit
```

[`Metrics`] умеет собирать данные о времени выполнения замыкания `f` под заданным
именем, возвращать все собранные на какой-то момент времени метрики, а также удаляет
все накопленные данные при вызове `.clear()`. `Metrics` ничего не знает про `AutoClosable`
и, в целом, `.clear()` может быть вызван несколько раз, поэтому `Releasable[Metrics]`
объявлен отдельно:

```scala
object Metrics:
  def apply(): Metrics = ???

  given Using.Releasable[Metrics] with
    def release(a: Metrics): Unit = a.clear()
```

Как же выглядит использование `Using` вместе с этими интерфейсами?

```scala
def sum(x: Int, y: Int): Int =
  Using(Logger("log")): log =>
    log.printLine(s"will sum x = $x and y = $y")
    x + y
```

Выполнив этот код в `scala-cli repl` мы увидим следующее:

```console
scala> sum(2, 2)
 -- Printer 'log' is acquired.
log: will sum x = 2 and y = 2
 -- Printer 'log' is released
val res6: Int = 4
```

1. Мы инициализировали логгер по имени `log`;
2. Напечатали строку;
3. Произвели вычисления;
4. "Освободили" логгер;
5. Вернули результат вычисления "наружу".

Как будет выглядеть более сложное совместное использование `Logger` и `Metrics`
в пределах одного метода?

```scala
def sumN_twice(n: Int): Seq[Int] =
  Using(Logger("log")): log =>
    Using(Metrics()): meter =>
      log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
      (0 to n).map: i =>
        meter("vector"):
          log.printLine(s"will sum range [$i, $n]")
          (i to n).foldLeft(0): (acc, i) =>
            meter("sum")(acc + i)
```

В данном куске кода уже начинают быть видны некоторые проблемы:

1. Появился заметный "сдвиг" ("дрифт") вправо с каждым иницилизированным
   ресурсом;
2. Мы забыли напечатать наши метрики. :(

Первую проблему не очень трудно поправить добавлением перегруженных методов `apply`
для нескольких ресурсов:

```scala
object Using:
  def apply[A1: Releasable, A2: Releasable, B](
    a1: A1, a2: => A2
  )(f: (A1, A2) => B): B =
      apply(a1)(a1 => apply(a2)(a2 => f(a1, a2)))
```

Попробуем использовать новый метод, заодно вспомнив про печать метрик:

```scala
def sumN(n: Int): Seq[Int] =
  Using(Logger("log"), Metrics()): (log, meter) =>
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
```

```console
scala> sumN(3)
-- Printer 'log' is acquired.
log: will sum 3 ranges [i..3] where i in [0..3]
log: will sum range [0, 3]
log: will sum range [1, 3]
log: will sum range [2, 3]
log: will sum range [3, 3]
log: metrics:
log:   sum -> 271.19999999999993 ± 240.35257435692256 (10 samples)
log:   vector -> 35505.25 ± 16556.91653622437 (4 samples)
-- Printer 'log' is released
val res7: Seq[Int] = Vector(6, 6, 5, 3)
```

Кажется, мы получили что хотели, но было бы неплохо каким-то образом научится
"не забывать" про печать метрик. Попробуем написать реализацию `Metrics` совмещенную
с логированием результатов:

```scala
object Metrics:
  def logging(): Metrics =
    Using(Logger("metrics")): log =>
      Logging(Metrics(), log)

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
```

Кажется, мы сделали все, что было нужно, попробуем это использовать:

```scala
def sumN_logging_uar(n: Int): Seq[Int] =
  Using(Logger("log"), Metrics.logging()): (log, meter) =>
    log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
    (0 to n).map: i =>
      meter("vector"):
        log.printLine(s"will sum range [$i, $n]")
        (i to n).foldLeft(0): (acc, i) =>
          meter("sum")(acc + i)
```

```console
scala> sumN_logging_uar(3)
 -- Printer 'log' is acquired.
 -- Printer 'metrics' is acquired.
 -- Printer 'metrics' is released
log: will sum 3 ranges [i..3] where i in [0..3]
log: will sum range [0, 3]
log: will sum range [1, 3]
log: will sum range [2, 3]
log: will sum range [3, 3]
metrics: !!! WARN !!! Use of printer 'metrics' after release.
metrics: collected metrics:
metrics: !!! WARN !!! Use of printer 'metrics' after release.
metrics:   sum -> 215.00 ± 159.92 (10 samples)
metrics: !!! WARN !!! Use of printer 'metrics' after release.
metrics:   vector -> 133451.00 ± 175937.21 (4 samples)
 -- Printer 'log' is released
val res8: Seq[Int] = Vector(6, 6, 5, 3)
```

Увы! Несмотря на (или благодаря?) использование `Using(Logger("metrics"))`,
инициализированный логгер был освобожден сразу после завершения метода
`Metrics.logger()` (как и должен был). Поэтому печать метрик привела к
ситуации 'use after release'.

К сожалению, на данный момент нам ничего не остается, кроме как полагаться
на экземпляр логгера, переданный извне:

```scala
object Metrics:
  def logging(log: Logger): Metrics =
    Logging(Metrics(), log)
```

```scala
def sumN_logging_using(n: Int): Seq[Int] =
  Using(Logger("log")): log =>
    Using(Metrics.logging(log)): meter =>
      log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
      (0 to n).map: i =>
        meter("vector"):
          log.printLine(s"will sum range [$i, $n]")
          (i to n).foldLeft(0): (acc, i) =>
            meter("sum")(acc + i)
```

```console
scala> sumN_logging_using(3)
 -- Printer 'log' is acquired.
log: will sum 3 ranges [i..3] where i in [0..3]
log: will sum range [0, 3]
log: will sum range [1, 3]
log: will sum range [2, 3]
log: will sum range [3, 3]
log: collected metrics:
log:   sum -> 168.60 ± 122.33 (10 samples)
log:   vector -> 35938.75 ± 25733.62 (4 samples)
 -- Printer 'log' is released
val res9: Seq[Int] = Vector(6, 6, 5, 3)
```

[`Logger`]: Logger.scala
[`Metrics`]: Metrics.scala

### Что делать с переданными ресурсами?

Отдельно стоит поговорить про операцию `underlying.close()`, которая происходит
на [84й строчке `Metrics.scala`](Metrics.scala#84): выполнять ее или нет -- вопрос
не из самых простых. В данном случае `Metrics.Logging` внутренний класс объекта
`Metrics` и мы точно знаем, что только мы имеем доступ к его конструктору и
передаем в него "свежий" экземпляр `Metrics.apply()`. Однако в общем случае это не так:
например, экземпляр `Logger` мы получаем "снаружи" и закрывать его после использования
скорее всего не самая лучшая идея.

Хотелось бы какой-то определенности, правда?

### `scala.util.Using.Manager`

Для того, чтобы было удобнее работать с несколькими ресурсами в пределах одного
метода, внутри `Using` существует дополнительный класс `Manager`:

```scala
final class Manager private () extends AutoCloseable:
  private var closed  = false
  private var handles = List.empty[() => Unit]

  def apply[A: Releasable](a: A): a.type =
    if !closed then handles = (() => summon[Releasable[A]].release(a)) :: handles
    else throw new IllegalStateException("Manager has already been closed")
    a

  def close(): Unit =
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

object Manager:
  def apply[A](f: Manager => A): A = Using(new Manager())(f(_))
```

Суть его довольно проста: через метод `.apply` _объекта_ `Manager` можно
получить экземпляр _класса_ `Manager`, вызывая внутренний `.apply` которого
можно регистрировать ресурсы в течение всего времени выполнения метода
(или блока кода).

Все зарегистрированные ресурсы будут освобождены в обратном порядке после
завершения выполнения блока, переданного в `Manager$.apply`:

```scala
def sumN_manager(n: Int): Seq[Int] =
  Using.Manager: use =>
    val log   = use(Logger("log"))
    val meter = use(Metrics.logging(log))
    log.printLine(s"will sum $n ranges [i..$n] where i in [0..$n]")
    (0 to n).map: i =>
      meter("vector"):
        log.printLine(s"will sum range [$i, $n]")
        (i to n).foldLeft(0): (acc, i) =>
          meter("sum")(acc + i)
```

```console
scala> sumN_manager(3)
 -- Printer 'log' is acquired.
log: will sum 3 ranges [i..3] where i in [0..3]
log: will sum range [0, 3]
log: will sum range [1, 3]
log: will sum range [2, 3]
log: will sum range [3, 3]
log: collected metrics:
log:   sum -> 240.30 ± 239.07 (10 samples)
log:   vector -> 38695.75 ± 29251.04 (4 samples)
 -- Printer 'log' is released
val res6: Seq[Int] = Vector(6, 6, 5, 3)
```

## Что нового в Scala 3?

`scala.util.Using` появился в Scala 2.13, и нашу реализацию тоже можно
было написать "по-старому", так чем же нам поможет использование Scala 3?
Например, [контестными функциями][Context Functions].

Что это такое? До Scala 3 мы могли объявить тип функции только как
`val f: (A1, .., An) => B`. К сожалению, это приводило к тому, что если мы хотели
использовать один или несколько аргументов в виде неявных параметров в теле функции,
необходимо было писать их отдельно, снабжая ключевым словом `implicit`:

```scala
def sum_implicit(x: Int, y: Int)(implicit log: Logger) = ???
def repeat(i: Int)(f: Logger => A): Vector[A] =
  val log = new Logger("repeat")
  (0 until i).map(_ => f(log))

// repeat(3)(_ => sum_implicit(2, 2)) — won’t compile
repeat(3) { implicit log => sum_implicit(2, 2) }
```

Теперь у нас есть возможность объявить (некоторые) аргументы типа функции параметрами
контекста, используя символ `?=>`. Такие параметры автоматически будут использованы
как `givens` внутри тела функции:

```scala
def sum_implicit(x: Int, y: Int)(using log: Logger) = ...
def repeat(i: Int)(f: Logger ?=> A): Vector[A] =
  val log = new Logger("repeat")
  (0 until i).map(_ => f(using log))

repeat(3)(sum_implicit(2, 2))
repeat(3): log ?=>
  log.printLine("before 'sum_implicit'")
  sum_implicit(2, 2)
  log.printLine("after 'sum_implicit'")
}
```

Хорошо, но что мы с этим можем сделать? Мы можем написать DSL, упрощающий
использование `Using`, но и предполагающий расширение. Для этого мы сперва несколько
перепишем [`Manager`][`managed`]:

```scala
final class Manager private[managed] ():
  private var closed  = false
  private var handles = List.empty[() => Unit]

  private[managed] def handle(f: () => Unit): Unit = ???
  private[managed] def close(): Unit = ???
```

Он отличается от `Using.Manager` только тем, что регистриует не экземпляры `A: Releasable`,
а любую функцию `() => Unit`. При этом код методов `.handle` и `.close` остался фактически
без изменений.

В дополнение к новому классу объявим функцию [`manage`][`managed`]:

```scala
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
```

Нетрудно заметить, что тело этой функции один-в-один повторяет `Using.Manager.apply`,
однако обрабатываемое замыкание `f` теперь контекстная функция.

Осталось определить еще пару утилитарных методов:

- `defer` позволяет зарегистрировать любой блок кода, возвращающий `Unit`, для выполнения
  "по окончании" `manage`;
- `use` регистрирует любой объект типа `A`, для которого существует `Releasable[A]`, повторяя
  "старый" `Using.Manager#apply`.

```scala
def defer(f: => Unit)(using M: Manager): Unit = M.handle(() => f)
def use[A](a: A)(using M: Manager, R: Using.Releasable[A]): A =
  defer(R.release(a))
  a
```

Давайте посмотрим, как изменится реализация наших тестовые методы с использованием этих фукнций:

```scala
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
```

Запустив этот код, можно убедится, что дополнительное логирование, переданное в `defer` происходит
после выполнения тела функции, но до освобождения экземпляров `Metrics` и `Logger`, как и было задумано:

```console
scala> sumN(3)
 -- Printer 'log' is acquired.
log: will sum 3 ranges [i..3] where i in [0..3]
log: will sum range [0, 3]
log: will sum range [1, 3]
log: will sum range [2, 3]
log: will sum range [3, 3]
log: 'sumN' completed
log: collected metrics:
log:   sum -> 241.80 ± 46.11 (10 samples)
log:   vector -> 37783.50 ± 10351.99 (4 samples)
 -- Printer 'log' is released
val res1: Seq[Int] = Vector(6, 6, 5, 3)
```

[Context Functions]: https://docs.scala-lang.org/scala3/reference/contextual/context-functions.html
[`managed`]: managed.scala

## `bracket` -- как много в этом слове...

Внимательный читатель уже заметил, конечно, что все методы, заведующие управлением ресурсами,
которые мы успели написать, устроены по одному шаблону:

1. Инициализация ресурса;
2. Работа с ресурсом;
3. Освобождение ресурса и возврат результата работы с ресурсом.

В мире ~зказок~, хм, функционального программирования подобный шаблон известен под именем
[`bracket`] и может быть записан в следующей обобщенной форме:

```scala
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
```

Используя подобную конструкцию мы могли бы переписать любой из примеров, реализованных выше:

```scala
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
```

Однако, как уже тоже наверняка заметил внимательный читатель, нам приходит раз за разом повторять
процедуры инициализации и освобождения объектов. Хочется каким-то образом делать это поменьше, а
с самим ресурсом работать побольше и попроще.

[`bracket`]: https://hackage.haskell.org/package/exceptions-0.10.8/docs/Control-Monad-Catch.html#v:bracket

## RAII: Resource Acquision is Initialization

В этом нам поможет программная идиома ["Resource Acquisition is Initialization"][RAII]
или, по-русски, "Получение ресурса есть инициализация".

Суть данной идиомы заключается в том, что процесс получения ресурса для использования становится
неотрывно связан с процессом его инициализации (например, в конструкторе), а процесс освобождения --
с уничтожением ресурса (например, в деструкторе).

В некоторых языках (Rust, C++) подобные механизм реализуется непосредственно средствами компилятора,
однако Scala/JVM не предлагает нам ничего подобного. Как же быть?

Давайте реализуем эту идиому в виде типажа [`Resource[A]`][`Resource`]:

```scala
trait Resource[A]:
  def allocate: (A, () => Unit)

  def use[B](f: A => B): B =
    val (a, release)       = allocate
    ...
  end use

  def map[B](f: A => B): Resource[B]               = Resource.Map(this, f)
  def flatMap[B](f: A => Resource[B]): Resource[B] = Resource.Bind(this, f)
end Resource

object Resource:
  def apply[A](acquire: => A)(release: A => Unit): Resource[A] = new Resource[A]:
    def allocate: (A, () => Unit) =
      val a = acquire
      (a, () => release(a))

  private[Resource] final class Map[A, B](
    underlying: Resource[A], f: A => B
  ) extends Resource[B]:
    def allocate: (B, () => Unit) = ???

  private[Resource] final class Bind[A, B](
    underlying: Resource[A], f: A => Resource[B]
  ) extends Resource[B]:
    def allocate: (B, () => Unit) = ???
end Resource
```

"Основным" методом нашего интерфейса является все тот же `.use(..)` похожий по сигнатуре и
реализации на многочисленные методы управления ресурсами выше, однако добавились и еще несколько:

- `.allocate` дает возможность пользователю получить полный контроль над ресурсом и фукнцией
  его освобождения;
- `.map` и `.flatMap` дают нам возможность монадической композиции ресурсов (например,
  через `for-comprehension`). Исходный код метода `allocate` для `Resource.Map` и `Resource.Bind`
  можно посмотреть в файле [`Resource.scala`][`Resource`] -- там нет ничего сложного, как всегда
  надо только аккуратно разобраться с последовательностью перехвата исключений и количеством мест,
  где они могут произойти.

Конструктор `Resource.apply` существенном отличается `Using.apply` тем, что:

1. Не предполагает наличия специальных интерфейсов вроде `AutoClosable` или `Releasable`;
2. Позволяет описывать процессы инициализации и освобождения фактически любого типа (или набора типов).

Например, мы можем прееписать конструктор экземпляров `Logger` как

```scala
object Logger:
  def resource(name: String): Resource[Logger] =
    Resource(Logger(name))(_.close())
```

и его использование будет не слишком отличаться от реализованного ранее:

```scala
def sum(x: Int, y: Int): Int = Logger
  .resource("log")
  .use: log =>
    log.printLine(s"will sum x = $x and y = $y")
    x + y
```

В случае `Metrics` можно пойти даже дальше, воспользовавшись фактом, что наша реализация
"метрик с логированием" никак не использует логгер вплоть до момента печати собранных метрик
перед очисткой. Соответственно, инициализировать логгер можно прямо перед печатью, а освобождать --
сразу после:

```scala
object Metrics:
  def resource(): Resource[Metrics] =
    Resource(Metrics())(_.clear())

  def lazyLogging(): Resource[Metrics] =
    resource().flatMap: origin =>
      val log = Logger.resource("metrics")
      Resource(LazyLogging(origin, log))(_.clear())

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
```

Кроме того, у нас теперь нет никакой необходимости решать вопрос "вызывать `underlying.clear()` или `log.close()` или нет?"
Тип данных метрик и логгера в виде `Resource[_]` однозначно указывает нам, что все необходимые действия по освобождению ресурсов
будут предприняты и без нашего участия.

Немного омрачает праздник лишь тот факт, что для одновременного использования логгера и метрик придется прибегать к монадической
композиции:

```scala
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
```

Как это упростить? Давайте добавим еще один метод, использующий наш новый [`Manager`][`managed`]:

```scala
def acquire[A](r: Resource[A])(using M: Manager): A =
  val (a, release) = r.allocate
  defer(release())
  a
```

Получается очень похоже на `direct style`, который сейчас так в моде:

```scala
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
```

```console
scala> sumN(3)
 -- Printer 'log' is acquired.
log: will sum 3 ranges [i..3] where i in [0..3]
log: will sum range [0, 3]
log: will sum range [1, 3]
log: will sum range [2, 3]
log: will sum range [3, 3]
 -- Printer 'metrics' is acquired.
metrics: collected metrics:
metrics:   sum -> 734.20 ± 1694.38 (10 samples)
metrics:   vector -> 47954.25 ± 34814.71 (4 samples)
 -- Printer 'metrics' is released
 -- Printer 'log' is released
val res6: Seq[Int] = Vector(6, 6, 5, 3)
```

[RAII]: https://en.wikipedia.org/wiki/Resource_acquisition_is_initialization
[`Resource`]: Resource.scala

## Немного метапрограммирования

Чтобы еще больше облегчить себе использование нескольких ресурсов одновременно,
вспомним про еще два нововведения в Scala3: изменение представления кортежей и `match types`.

### Кортежи

В отличие от Scala 2.13, где представлением для кортежей фактически был набор кейс-классов
`Tuple1[A1](_1: A1), ... Tuple22[A1, .., A22](_1: A1, ..., _22: A22)`, в Scala 3 кортежи
больше похожи на гетерогенные списки из [`shapeless`]:

```scala
sealed trait Tuple

case object EmptyTuple extends Tuple
sealed trait NonEmptyTuple extends Tuple:
  def head: Head[this.type]
  def tail: Tail[this.type]

sealed abstract class *:[+H, +T <: Tuple]
  extends NonEmptyTuple
```

Теперь любой кортеж вида `(A, B, C)` представляет собой похожую на список структуру типов
`A *: B *: C *: EmptyTuple`, и точно также предоставляет операции получения первого элемента (`.head`),
последнего элемента (`.last`) и другие.

[`shapeless`]: https://github.com/milessabin/shapeless/blob/main/core/shared/src/main/scala/shapeless/hlists.scala

### Match Types

Что же такое странные типы `Head[this.type]` и `Tail[this.type]` в объявлении выше? Это те самые
[Match Types], то есть типы, конкретный тип которых в момент компиляции зависит от типа-параметра:

```scala
type Head[X <: NonEmptyTuple] = X match
  case h *: _ => h

type Tail[X <: NonEmptyTuple] = X match
  case _ *: t => t
```

В данном случае `Head[_]` всегда будет совпадать с типом первого элемента кортежа, а `Tail[_]` -- c
типом кортежа их остальных элементов (или `EmptyTuple`, если он пуст).

[Match Types]: https://docs.scala-lang.org/scala3/reference/new-types/match-types.html

### `managing`

В нашем случае мы можем объявить `match type` следующего вида:

```scala
type ResourceParams[X <: NonEmptyTuple] <: NonEmptyTuple = X match
  case Resource[a] *: EmptyTuple => a *: EmptyTuple
  case Resource[a] *: tail       => a *: ResourceParams[tail]
```

При помощи данного типа мы можем определить кортеж типов ресурсов "управляемых" кортежем типов `Resource[?]`:

```scala
scala> type A = ResourceParams[(Resource[Logger], Resource[Metrics])]
// defined alias type A = (Logger, Metrics)
```

С использованием этого типа мы можем написать функцию управления множеством ресурсов:

```scala
def managing[X <: NonEmptyTuple, A](rs: X)(using ev: Tuple.Union[X] <:< Resource[?])(
    f: ResourceParams[X] => Manager ?=> A
): A = manage:
  def loop(rest: NonEmptyTuple, acc: Tuple): NonEmptyTuple = (rest: @unchecked) match
    case (r: Resource[a]) *: EmptyTuple            => acc :* acquire(r)
    case (r: Resource[a]) *: (tail: NonEmptyTuple) => loop(tail, acc :* acquire(r))

  f(loop(rs, EmptyTuple).asInstanceOf[ResourceParams[X]])
```

С использованием этой функции наш код становится максимально похож на `try-with-resources`
(за вычетом внутренних зависимостей между ресурсами, увы, но для этого есть `acquire`):

```scala
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
```

```console
scala> sumN_manage(3)
 -- Printer 'log' is acquired.
log: will sum 3 ranges [i..3] where i in [0..3]
log: will sum range [0, 3]
log: will sum range [1, 3]
log: will sum range [2, 3]
log: will sum range [3, 3]
 -- Printer 'metrics' is acquired.
metrics: collected metrics:
metrics:   sum -> 255.40 ± 260.76 (10 samples)
metrics:   vector -> 388021.75 ± 591805.62 (4 samples)
 -- Printer 'metrics' is released
 -- Printer 'log' is released
val res7: Seq[Int] = Vector(6, 6, 5, 3)
```

Хочется отменить, что при этом мы получили поддержку _произвольного_ количества аргументов в `managing`,
а типы ресурсов, которые будут переданы в замыкание `f` известны статически в момент компиляции.

## Светлое будущее: 'Caprese'

К сожалению, у нас все еще осталась проблема использования ресурсов после их освобождения.

Мы можем легко представить себе ситуацию, когда кто-то рели не задавать имя метрики каждый раз,
а вынести запись каждой именованной метрики в отдельную функцию:

```scala
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
```

Увы, запустив это код, мы не только не увидим наших метрик, но и (что существенно хуже),
даже не получим никакого предупреждения от логгера, как это было в начала статьи, так как
"с точки зрения" наших ресурсов все прошло нормально: метрики инициализировали и сразу очистили.

Это происходит из-за отсутствия на данный момент возможности отследить на уровне компилятора Scala
время жизни (или доступности для использования другими) объекта.

Однако, в скором будущем это изменится благодаря проекту ['Caprese: Capabilities for resources and effects'][Caprese],
часть которого уже доступна в версиях Scala 3.4 и 3.5 под флагом `-experimental`:

```scala
import scala.language.experimental.captureChecking

object Metrics:
  def logging(log: Logger^): Metrics^{log} = ???

// won't compile
def createMetrics(): Metrics =
  val log: Logger^           = Logger("log")
  val metrics: Metrics^{log} = Metrics.logging(log)

  metrics
```

В данном случае при помощи нового синтаксиса `^{...}` мы сообщили компилятору что:

1. метод `Metrics.logging` принимает `Logger` только с известным "временем жизни" (вольный перевод термина
   `capability` автором, более похожий на `lifetime` из Rust);
2. тот же метод возвращает экзепляр `Metrics^{log}`, который зависит от "времени жизни" переданного
   `log: Logger^`, т.е. не может существовать дольше.

С точки зрения `capability`, любой "чистый" тип (т.е. тип, не зависящий от других capabilities) является потомком
такого же типа, но с известными capabilities, которые, в свою очередь являются потомками типа с универсальной
capability `^{cap}` (или просто `^`): `Metrics <: Metrics^{log} <: Metrics^`.

В результате, если мы попытаемся вернуть из метода `def createMetrics(): Metrics` объект с типом `Metrics^{log}`,
нам это запретит компилятор.

Также меняется и синтаксис типов функций:

1. `f: A -> B` теперь означает "чистую" функцию, т.е. не зависящую (не захватывающую) от каких-либо capability;
2. `f: A ->{log} B` означает функцию, тело которой может как-либо использовать переменную `log`, а, следовательно,
   доступную только одновременно с ней;
3. `f: A => B` означает то же, что и `f: A ->{cap} B`, то есть старый добрый функции, захватывающие все, что угодно
   и никак это не отслеживающие.

Более подробно про [Capture Checking] можно прочитать в Scala Reference, мы же попытаемся использовать  это
на практике, изменив немного реализацию [`Resource`][`captured`]:

```scala
trait Resource[A]:
  def allocate: (A, () -> Unit)
  def use[B](f: A^ => B): B = ???

object Resource:
  def apply[A](acquire: -> A)(release: A -> Unit): Resource[A] = ???
```

- `acquire` и `release` стали "чистыми функциями, т.е. мы может инициализировать и освобождать объекты когда угодно;
- `use` теперь принимает замыкание типа `A^ => B`, что означает, что мы отслеживаем capability типа A, но возвращаемый
  тип `B` от нее не зависит.

Попробовав использовать новую реализацию так, чтобы захватить экземпляр `log` после его освобождения мы получим
ошибку компиляции:

```scala
def sum_error(x: Int, y: Int): Int =
  val print = logger("log").use: log =>
    (s: String) => log.printLine(s)
  print(s"will sum x = $x and y = $y")
  x + y
```

```console
$> scala-cli run project.scala captured.scala
Compiling project (Scala 3.4.2, JVM (21))
[error] ./captured.scala:105:17
[error] local reference log leaks into
[error] outer capture set of type parameter B of method use
[error]     val print = logger("log").use: log =>
[error]
```

Добавим аналогичные изменим в реализацию [`Manager`][`captured`]:

```scala
@capability
final class Manager private[captured] ():
  self =>
  private var handles: List[() ->{self} Unit]^{self} = Nil
  private[captured] def handle(f: () ->{self} Unit): Unit = ???
  ...
```

Нам потребовалось добавить аннотацию `@capability`, означающую, что экземпляры `Manager` всегда отслеживают
capabilities и нам не надо писать `Manager^`, а также изменить тип замыкания для `handle` и `handles` на
`() ->{self} Unit`, указав тем самым, что зарегистрированные функции по своим возможностям не могут превосходить
`Manager`.

Также изменим сигнатуру функции `defer`, чтобы она указывала, что и любое переданное в нее замыкание не может
пережить `Manager`:

```scala
def defer(using M: Manager)(f: ->{M} Unit): Unit = M.handle(() => f)
```

Фактически, изменение сигнатур методов -- это все, что нам потребовалось сделать, реализации остались точно такими же.
Теперь, то тех пор пока мы используем `log` внутри блока `manage` все хорошо:

```scala
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
```

Однако, как только мы попытаемся захватить печать в лог в функцию, использующуюся вне пределов блока
`manage` мы получим ошибку компиляции:

```scala
def sumN_error(n: Int): Seq[Int] =
  val print = manage:
    val log = acquire(logger("log"))
    (s: String) => log.printLine(s)

  print(s"will sum $n ranges [i..$n] where i in [0..$n]")
  (0 to n).map: i =>
      print(s"will sum range [$i, $n]")
      (i to n).foldLeft(0): (acc, i) =>
        acc + i
```

```console
$> scala-cli run project.scala captured.scala
Compiling project (Scala 3.4.2, JVM (21))
[error] ./captured.scala:132:17
[error] local reference contextual$3 leaks into
[error] outer capture set of type parameter A of method manage
[error]     val print = manage:
[error]
```

О дивный новый мир! Ура!

Разумеется, данная функциональность не зря идет под флагом `-experimental` в компиляторе и требует отдельного импорта:
синтаксис не совсем понятен, ошибки весьма загадочны и так далее. Однако, есть надежда что в скором времени она
достаточно подрастет для того, чтобы с ресурсами в Scala можно было работать еще удобнее и безопаснее.

[Caprese]: https://www.slideshare.net/slideshow/capabilities-for-resources-and-effects-252161040/252161040#1
[Capture Checking]: https://docs.scala-lang.org/scala3/reference/experimental/cc.html
[`captured`]: captured.scala

Вместо заключения -- ссылки на несколько реализаций средств управления ресурсами, которыми автор вдохновлялся
в процессе подготовки этого материала:

- [`scala.util.Using`]
- [`twitter.util.Managed`](https://twitter.github.io/util/docs/com/twitter/util/Managed.html)
- [`cats.effect.Resource`](https://typelevel.org/cats-effect/docs/std/resource)
- [`zio.Scope`](https://zio.dev/reference/resource/scope/)

---

(c) Copyright Alexei Shuksto, 2024
