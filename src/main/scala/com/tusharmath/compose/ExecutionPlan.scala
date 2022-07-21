package com.tusharmath.compose
import zio.schema.{DeriveSchema, DynamicValue, Schema}
import zio.schema.ast.SchemaAst
import zio.schema.codec.JsonCodec
import zio.{Chunk, Task}

sealed trait ExecutionPlan { self =>
  def json: String                                           = new String(binary.toArray)
  def binary: Chunk[Byte]                                    = JsonCodec.encode(ExecutionPlan.schema)(self)
  def unsafeExecute(value: DynamicValue): Task[DynamicValue] = Executor.execute(self, value)
}

object ExecutionPlan {

  def fromLambda[A, B](lmb: ZLambda[A, B]): ExecutionPlan =
    lmb match {
      case ZLambda.Pipe(f, g)            => Sequence(fromLambda(f), fromLambda(g))
      case ZLambda.Zip2(f1, f2, s1, s2)  => Zip2(fromLambda(f1), fromLambda(f2), s1.ast, s2.ast)
      case ZLambda.FromMap(i, source, o) => Dictionary(source.map { case (k, v) => (i.toDynamic(k), o.toDynamic(v)) })
      case ZLambda.Select(input, path, output)  => Select(path)
      case ZLambda.Constant(b, schema)          => Constant(schema.toDynamic(b))
      case ZLambda.Identity()                   => Identity
      case ZLambda.AddInt                       => AddInt
      case ZLambda.Partial11(f, a1, s1)         => Partial(fromLambda(f), List(s1.ast), List(s1.toDynamic(a1)))
      case ZLambda.Partial21(f, a1, s1, s2)     => Partial(fromLambda(f), List(s1.ast, s2.ast), List(s1.toDynamic(a1)))
      case ZLambda.Partial22(f, a1, a2, s1, s2) =>
        Partial(fromLambda(f), List(s1.ast, s2.ast), List(s1.toDynamic(a1), s2.toDynamic(a2)))
    }

  case class Constant(value: DynamicValue)                                                   extends ExecutionPlan
  case class Zip2(e1: ExecutionPlan, e2: ExecutionPlan, s1: SchemaAst, s2: SchemaAst)        extends ExecutionPlan
  case class Sequence(first: ExecutionPlan, second: ExecutionPlan)                           extends ExecutionPlan
  case class Dictionary(value: Map[DynamicValue, DynamicValue])                              extends ExecutionPlan
  case class Select(path: List[String])                                                      extends ExecutionPlan
  case class Partial(f: ExecutionPlan, argSchema: List[SchemaAst], args: List[DynamicValue]) extends ExecutionPlan
  case object AddInt                                                                         extends ExecutionPlan
  case object Identity                                                                       extends ExecutionPlan

  implicit val schema = DeriveSchema.gen[ExecutionPlan]
}
