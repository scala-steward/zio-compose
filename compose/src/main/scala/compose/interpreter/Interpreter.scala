package compose.interpreter

import zio.{Task, UIO, ZIO}
import zio.schema.{DynamicValue, Schema}
import compose.{~>, ExecutionPlan}
import compose.ExecutionPlan.Scoped.{ContextId, RefId}
import zio.schema.codec.JsonCodec

import scala.annotation.tailrec
import scala.collection.immutable.ListMap

trait Interpreter {
  def eval[B](lmb: Any ~> B)(implicit b: Schema[B]): Task[B] =
    eval[B](lmb.compile, Schema.primitive[Unit].toDynamic(()))

  def eval[A](plan: ExecutionPlan, value: DynamicValue)(implicit ev: Schema[A]): Task[A] =
    evalDynamic(plan, value).flatMap(value => Interpreter.effect(value.toTypedValue(ev)))

  def evalDynamic[B](lmb: Any ~> B)(implicit b: Schema[B]): Task[DynamicValue] =
    evalDynamic(lmb.compile, Schema.primitive[Unit].toDynamic(()))

  def evalDynamic(plan: ExecutionPlan, input: DynamicValue): Task[DynamicValue]

  def evalJson[B](lmb: Any ~> B)(implicit b: Schema[B]): Task[String] =
    evalDynamic(lmb).map(res => new String(JsonCodec.encode(Schema[DynamicValue])(res).toArray))
}

object Interpreter {
  def effect[E, A](e: Either[String, A])(implicit trace: zio.Trace): Task[A] =
    e match {
      case Left(error) => ZIO.fail(new Exception(error))
      case Right(a)    => ZIO.succeed(a)
    }

  def eval[B](f: Any ~> B)(implicit ev: Schema[B]): Task[B] =
    for {
      int <- Interpreter.inMemory
      res <- int.eval(f)
    } yield res

  def inMemory: UIO[Interpreter] =
    ScopeContext.inMemory[ContextId, RefId, DynamicValue].map(scope => InMemoryInterpreter(scope))

  final case class InMemoryInterpreter(scope: ScopeContext[ContextId, RefId, DynamicValue]) extends Interpreter {
    import ExecutionPlan._
    def evalDynamic(plan: ExecutionPlan, input: DynamicValue): Task[DynamicValue] = {
      plan match {
        case Debugger(operation)     => evalDynamic(input, operation)
        case Textual(operation)               => evalDynamic(input, operation)
        case Scoped(operation)     => evalDynamic(input, operation)
        case Tupled(operation)     => evalDynamic(input, operation)
        case Recursive(operation) => evalDynamic(input, operation)
        case Sources(operation)    => evalDynamic(input, operation)
        case Optical(operation)            => evalDynamic(input, operation)
        case Logical(operation)            => evalDynamic(input, operation)
        case Numeric(operation, kind)      => evalDynamic(input, operation, kind)
        case Arrow(operation)              => evalDynamic(input, operation)
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Arrow.Operation): Task[DynamicValue] = {
      operation match {
        case Arrow.Zip(left, right) =>
          for {
            a <- evalDynamic(left, input)
            b <- evalDynamic(right, input)
          } yield DynamicValue.Tuple(a, b)

        case Arrow.Pipe(first, second) =>
          for {
            input  <- evalDynamic(first, input)
            output <- evalDynamic(second, input)
          } yield output

        case Arrow.Identity => ZIO.succeed(input)
      }
    }

    private def evalDynamic(
      input: DynamicValue,
      operation: Numeric.Operation,
      kind: Numeric.Kind,
    ): Task[DynamicValue] = {
      kind match {
        case Numeric.Kind.IntNumber =>
          operation match {
            case Numeric.Add(left, right)                =>
              for {
                a <- eval[Int](left, input)
                b <- eval[Int](right, input)
              } yield toDynamic(a + b)
            case Numeric.Multiply(left, right)           =>
              for {
                a <- eval[Int](left, input)
                b <- eval[Int](right, input)
              } yield toDynamic(a * b)
            case Numeric.Divide(left, right)             =>
              for {
                a <- eval[Int](left, input)
                b <- eval[Int](right, input)
              } yield toDynamic(a / b)
            case Numeric.GreaterThan(left, right)        =>
              for {
                a <- eval[Int](left, input)
                b <- eval[Int](right, input)
              } yield toDynamic(a > b)
            case Numeric.GreaterThanEqualTo(left, right) =>
              for {
                a <- eval[Int](left, input)
                b <- eval[Int](right, input)
              } yield toDynamic(a >= b)
            case Numeric.Negate(plan)                    =>
              for {
                a <- eval[Int](plan, input)
              } yield toDynamic(-a)
          }
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Logical.Operation): Task[DynamicValue] = {
      operation match {
        case Logical.And(left, right) =>
          for {
            left  <- eval[Boolean](left, input)
            right <- eval[Boolean](right, input)
          } yield toDynamic {
            left && right
          }
        case Logical.Or(left, right)  =>
          for {
            left  <- eval[Boolean](left, input)
            right <- eval[Boolean](right, input)
          } yield toDynamic {
            left || right
          }

        case Logical.Not(plan) =>
          for {
            bool <- eval[Boolean](plan, input)
          } yield toDynamic(!bool)

        case Logical.Equals(left, right) =>
          for {
            left  <- evalDynamic(left, input)
            right <- evalDynamic(right, input)
          } yield toDynamic(left == right)

        case Logical.Diverge(cond, ifTrue, ifFalse) =>
          for {
            cond   <- eval[Boolean](cond, input)
            result <- if (cond) evalDynamic(ifTrue, input) else evalDynamic(ifFalse, input)
          } yield result
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Optical.Operation): Task[DynamicValue] = {
      operation match {
        case Optical.GetPath(path) =>
          input match {
            case DynamicValue.Record(values) =>
              @tailrec
              def loop(
                path: List[String],
                values: ListMap[String, DynamicValue],
              ): Either[Exception, DynamicValue] = {
                path match {
                  case Nil          => Left(new Exception("Path not found"))
                  case head :: tail =>
                    values.get(head) match {
                      case None    => Left(new Exception("Path not found"))
                      case Some(v) =>
                        if (tail.isEmpty) Right(v)
                        else
                          loop(tail, v.asInstanceOf[DynamicValue.Record].values)
                    }
                }
              }

              ZIO.fromEither(loop(path, values))
            case _                           => ZIO.fail(new Exception("Select only works on records"))
          }

        case Optical.SetPath(path) =>
          input match {
            case DynamicValue.Tuple(DynamicValue.Record(values), input) =>
              def loop(
                path: List[String],
                values: ListMap[String, DynamicValue],
                a: DynamicValue,
              ): Either[Exception, DynamicValue] = {
                path match {
                  case Nil          => Left(new Exception("Path not found"))
                  case head :: tail =>
                    values.get(head) match {
                      case None    => Left(new Exception("Path not found"))
                      case Some(v) =>
                        if (tail.isEmpty) Right(DynamicValue.Record(values + (head -> a)))
                        else
                          loop(tail, v.asInstanceOf[DynamicValue.Record].values, a) map { value =>
                            DynamicValue.Record(values + (head -> value))
                          }
                    }
                }
              }

              ZIO.fromEither(loop(path, values, input))
            case input => ZIO.fail(new Exception(s"Set path doesn't work on: ${input}"))
          }
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Sources.Operation): Task[DynamicValue] = {
      operation match {
        case Sources.Default(value)  => ZIO.succeed(value)
        case Sources.FromMap(value)  =>
          ZIO.succeed(value.get(input) match {
            case Some(value) => DynamicValue.SomeValue(value)
            case None        => DynamicValue.NoneValue
          })
        case Sources.Constant(value) => ZIO.succeed(value)
        case Sources.WriteLine       =>
          for {
            string <- Interpreter.effect(input.toTypedValue(Schema[String]))
            _      <- zio.Console.printLine(string)
          } yield unit
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Recursive.Operation): Task[DynamicValue] = {
      operation match {
        case Recursive.RepeatWhile(f, cond) =>
          def loop(input: DynamicValue): Task[DynamicValue] = {
            for {
              output <- evalDynamic(f, input)
              isTrue <- eval[Boolean](cond, output)
              result <- if (isTrue) loop(output) else ZIO.succeed(output)
            } yield result
          }

          loop(input)

        case Recursive.DoWhile(f, cond) =>
          def loop: Task[DynamicValue] = {
            for {
              output <- evalDynamic(f, input)
              isTrue <- eval[Boolean](cond, input)
              result <- if (isTrue) loop else ZIO.succeed(output)
            } yield result
          }

          loop
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Tupled.Operation): Task[DynamicValue] = {
      operation match {
        case Tupled.Arg(plan, i) =>
          for {
            input  <- evalDynamic(plan, input)
            result <- input match {
              case DynamicValue.Tuple(left, right) =>
                i match {
                  case 0 => ZIO.succeed(left)
                  case 1 => ZIO.succeed(right)
                  case n =>
                    ZIO.fail(
                      new RuntimeException(s"Can not extract element at index ${n} from ${input}"),
                    )
                }
              case _ => ZIO.fail(new RuntimeException(s"Can not extract args from ${input}"))
            }
          } yield result
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Scoped.Operation): Task[DynamicValue] = {
      operation match {
        case Scoped.SetScope(refId, ctxId) =>
          for {
            _ <- scope.set(ctxId, refId, input)
          } yield toDynamic(())

        case Scoped.GetScope(refId, ctxId, value) =>
          for {
            option <- scope.get(ctxId, refId)
            value  <- option match {
              case Some(value) => ZIO.succeed(value)
              case None        => ZIO.succeed(value)
            }
          } yield value

        case Scoped.WithinScope(plan, ctxId) =>
          for {
            result <- evalDynamic(plan, input)
            _      <- scope.delete(ctxId)
          } yield result
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Textual.Operation): Task[DynamicValue] = {
      operation match {
        case Textual.Length(plan) =>
          for {
            str <- eval[String](plan, input)
          } yield toDynamic(str.length)

        case Textual.UpperCase(plan) =>
          for {
            str <- eval[String](plan, input)
          } yield toDynamic(str.toUpperCase)

        case Textual.LowerCase(plan) =>
          for {
            str <- eval[String](plan, input)
          } yield toDynamic(str.toLowerCase)

        case Textual.StartsWith(self, other) =>
          for {
            str1 <- eval[String](self, input)
            str2 <- eval[String](other, input)
          } yield toDynamic(str1.startsWith(str2))

        case Textual.EndsWith(self, other) =>
          for {
            str1 <- eval[String](self, input)
            str2 <- eval[String](other, input)
          } yield toDynamic(str1.endsWith(str2))

        case Textual.Contains(self, other) =>
          for {
            str1 <- eval[String](self, input)
            str2 <- eval[String](other, input)
          } yield toDynamic(str1.contains(str2))

        case Textual.Concat(self, other) =>
          for {
            str1 <- eval[String](self, input)
            str2 <- eval[String](other, input)
          } yield toDynamic(str1 ++ str2)
      }
    }

    private def evalDynamic(input: DynamicValue, operation: Debugger.Operation): Task[DynamicValue] = {
      operation match {
        case Debugger.Debug(plan, name) =>
          for {
            result <- evalDynamic(plan, input)
            json = new String(JsonCodec.encode(Schema[DynamicValue])(result).toArray)
            _ <- zio.Console.printLine(s"${name}: $json")
          } yield result
        case Debugger.Show(plan, name)  =>
          val json = plan.json
          zio.Console.printLine(s"${name}: $json") *> evalDynamic(plan, input)
      }
    }

    def toDynamic[A](a: A)(implicit schema: Schema[A]): DynamicValue =
      schema.toDynamic(a)

    def unit: DynamicValue = Schema.primitive[Unit].toDynamic(())
  }
}
