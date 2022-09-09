package compose.dsl
import compose._

trait RandomDSL {
  import Lambda._
  import compose.ExecutionPlan.Random

  def randomInt: Any ~> Int                                = randomInt(constant(Int.MinValue), constant(Int.MaxValue))
  def randomInt[A](min: A ~> Int, max: A ~> Int): A ~> Int = attempt[Any, Int](Random.NextInt(min.compile, max.compile))
  def randomInt[A](min: Int, max: Int): Any ~> Int         = randomInt(constant(min), constant(max))
}
