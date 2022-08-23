package compose.dsl

import compose.~>
import compose.Lambda.{constant, make}
import compose.dsl.NumericDSL.IsNumeric
import compose.ExecutionPlan.Numeric
import zio.schema.Schema

trait NumericDSL[-A, +B] { self: A ~> B =>
  final def /[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> B1 =
    self divide other

  final def >[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    self gt other

  final def >=[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    self gte other

  final def <[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    self lt other

  final def <=[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    self lte other

  final def -[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> B1 =
    self + other.negate

  final def +[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> B1 =
    make[A1, B1](Numeric(Numeric.Add(self.compile, other.compile), num.kind))

  final def *[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> B1 =
    self multiply other

  final def between[A1 <: A, B1 >: B](min: A1 ~> B1, max: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    (self gte min) && (self lte max)

  final def between[B1 >: B](min: B1, max: B1)(implicit num: IsNumeric[B1], s: Schema[B1]): A ~> Boolean =
    between(constant(min), constant(max))

  final def dec[B1 >: B](implicit ev: IsNumeric[B1], s: Schema[B1]): A ~> B1 =
    self - constant(ev.one)

  final def divide[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> B1 =
    make[A1, B1](Numeric(Numeric.Divide(self.compile, other.compile), num.kind))

  final def gt[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    make[A1, Boolean](Numeric(Numeric.GreaterThan(self.compile, other.compile), num.kind))

  final def gte[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    make[A1, Boolean](Numeric(Numeric.GreaterThanEqualTo(self.compile, other.compile), num.kind))

  final def inc[B1 >: B](implicit ev: IsNumeric[B1], s: Schema[B1]): A ~> B1 =
    self + constant(ev.one)

  final def lt[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    (self >= other).not

  final def lte[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> Boolean =
    (self gt other).not

  final def multiply[A1 <: A, B1 >: B](other: A1 ~> B1)(implicit num: IsNumeric[B1]): A1 ~> B1 =
    make[A1, B1](Numeric(Numeric.Multiply(self.compile, other.compile), num.kind))

  final def negate[B1 >: B](implicit num: IsNumeric[B1]): A ~> B1 = {
    make[A, B1](Numeric(Numeric.Negate(self.compile), num.kind))
  }

}

object NumericDSL {

  sealed trait IsNumeric[A] {
    def kind: Numeric.Kind

    def one: A

    def zero: A
  }

  implicit case object IsInt extends IsNumeric[Int] {
    override def kind: Numeric.Kind = Numeric.Kind.IntNumber

    override def one: Int = 1

    override def zero: Int = 0
  }

}
