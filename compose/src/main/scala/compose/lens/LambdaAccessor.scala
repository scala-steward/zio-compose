package compose.lens

import zio.schema.{AccessorBuilder, Schema}

object LambdaAccessor extends AccessorBuilder {

  override type Lens[F, S, A]   = LambdaLens[S, A]
  override type Prism[F, S, A]  = Unit
  override type Traversal[S, A] = Unit

  override def makeLens[F, S, A](product: Schema.Record[S], term: Schema.Field[A]): Lens[F, S, A] =
    new LambdaLens(term = term)

  override def makePrism[F, S, A](sum: Schema.Enum[S], term: Schema.Case[A, S]): Prism[F, S, A] = ()

  override def makeTraversal[S, A](collection: Schema.Collection[S, A], element: Schema[A]): Traversal[S, A] = ???
}
