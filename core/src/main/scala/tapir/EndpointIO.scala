package tapir

import tapir.Codec.PlainCodec
import tapir.CodecForMany.PlainCodecForMany
import tapir.internal.ProductToParams
import tapir.model.{Method, MultiQueryParams}
import tapir.typelevel.{FnComponents, ParamConcat, ParamsAsArgs}

sealed trait EndpointInput[I] {
  def and[J, IJ](other: EndpointInput[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): EndpointInput[IJ]
  def /[J, IJ](other: EndpointInput[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): EndpointInput[IJ] = and(other)

  def show: String

  def map[II](f: I => II)(g: II => I)(implicit paramsAsArgs: ParamsAsArgs[I]): EndpointInput[II] =
    EndpointInput.Mapped(this, f, g, paramsAsArgs)

  def mapTo[COMPANION, CASE_CLASS <: Product](c: COMPANION)(implicit fc: FnComponents[COMPANION, I, CASE_CLASS],
                                                            paramsAsArgs: ParamsAsArgs[I]): EndpointInput[CASE_CLASS] = {
    map[CASE_CLASS](fc.tupled(c).apply)(ProductToParams(_, fc.arity).asInstanceOf[I])(paramsAsArgs)
  }

  private[tapir] def asVectorOfSingle: Vector[EndpointInput.Single[_]] = this match {
    case s: EndpointInput.Single[_]   => Vector(s)
    case m: EndpointInput.Multiple[_] => m.inputs
    case m: EndpointIO.Multiple[_]    => m.ios
  }

  private[tapir] def asVectorOfBasic(includeAuth: Boolean = true): Vector[EndpointInput.Basic[_]] = this match {
    case b: EndpointInput.Basic[_]              => Vector(b)
    case EndpointInput.Mapped(wrapped, _, _, _) => wrapped.asVectorOfBasic(includeAuth)
    case EndpointIO.Mapped(wrapped, _, _, _)    => wrapped.asVectorOfBasic(includeAuth)
    case EndpointInput.Multiple(inputs)         => inputs.flatMap(_.asVectorOfBasic(includeAuth))
    case EndpointIO.Multiple(ios)               => ios.flatMap(_.asVectorOfBasic(includeAuth))
    case a: EndpointInput.Auth[_]               => if (includeAuth) a.input.asVectorOfBasic(includeAuth) else Vector.empty
  }

  private[tapir] def bodyType: Option[RawValueType[_]] = this match {
    case b: EndpointIO.Body[_, _, _]            => Some(b.codec.meta.rawValueType)
    case EndpointInput.Multiple(inputs)         => inputs.flatMap(_.bodyType).headOption
    case EndpointIO.Multiple(inputs)            => inputs.flatMap(_.bodyType).headOption
    case EndpointInput.Mapped(wrapped, _, _, _) => wrapped.bodyType
    case EndpointIO.Mapped(wrapped, _, _, _)    => wrapped.bodyType
    case a: EndpointInput.Auth[_]               => a.input.bodyType
    case _                                      => None
  }

  private[tapir] def method: Option[Method] = this match {
    case i: EndpointInput.RequestMethod         => Some(i.m)
    case EndpointInput.Multiple(inputs)         => inputs.flatMap(_.method).headOption
    case EndpointIO.Multiple(inputs)            => inputs.flatMap(_.method).headOption
    case EndpointInput.Mapped(wrapped, _, _, _) => wrapped.method
    case EndpointIO.Mapped(wrapped, _, _, _)    => wrapped.method
    case _                                      => None
  }

  private[tapir] def auths: Vector[EndpointInput.Auth[_]] = this match {
    case a: EndpointInput.Auth[_]               => Vector(a)
    case EndpointInput.Multiple(inputs)         => inputs.flatMap(_.auths)
    case EndpointIO.Multiple(inputs)            => inputs.flatMap(_.auths)
    case EndpointInput.Mapped(wrapped, _, _, _) => wrapped.auths
    case EndpointIO.Mapped(wrapped, _, _, _)    => wrapped.auths
    case _                                      => Vector.empty
  }

  // TODO: add generic traverse
}

object EndpointInput {
  sealed trait Single[I] extends EndpointInput[I] {
    def and[J, IJ](other: EndpointInput[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): EndpointInput[IJ] =
      other match {
        case s: Single[_]             => Multiple(Vector(this, s))
        case Multiple(inputs)         => Multiple(this +: inputs)
        case EndpointIO.Multiple(ios) => Multiple(this +: ios)
      }
  }

  sealed trait Basic[I] extends Single[I]

  case class RequestMethod(m: Method) extends Basic[Unit] {
    def show: String = m.m
  }

  case class PathSegment(s: String) extends Basic[Unit] {
    def show = s"/$s"
  }

  case class PathCapture[T](codec: PlainCodec[T], name: Option[String], info: EndpointIO.Info[T]) extends Basic[T] {
    def name(n: String): PathCapture[T] = copy(name = Some(n))
    def description(d: String): PathCapture[T] = copy(info = info.description(d))
    def example(t: T): PathCapture[T] = copy(info = info.example(t))
    def show = s"/[${name.getOrElse("")}]"
  }

  case class PathsCapture(info: EndpointIO.Info[Seq[String]]) extends Basic[Seq[String]] {
    def description(d: String): PathsCapture = copy(info = info.description(d))
    def example(t: Seq[String]): PathsCapture = copy(info = info.example(t))
    def show = s"/[multiple paths]"
  }

  case class Query[T](name: String, codec: PlainCodecForMany[T], info: EndpointIO.Info[T]) extends Basic[T] {
    def description(d: String): Query[T] = copy(info = info.description(d))
    def example(t: T): Query[T] = copy(info = info.example(t))
    def show = s"?$name"
  }

  case class QueryParams(info: EndpointIO.Info[MultiQueryParams]) extends Basic[MultiQueryParams] {
    def description(d: String): QueryParams = copy(info = info.description(d))
    def example(t: MultiQueryParams): QueryParams = copy(info = info.example(t))
    def show = s"?{multiple params}"
  }

  //

  trait Auth[T] extends EndpointInput.Single[T] {
    def input: EndpointInput.Single[T]
  }

  object Auth {
    case class ApiKey[T](input: EndpointInput.Single[T]) extends Auth[T] {
      def show = s"auth(api key, via ${input.show})"
    }
    case class Http[T](scheme: String, input: EndpointInput.Single[T]) extends Auth[T] {
      def show = s"auth($scheme http, via ${input.show})"
    }
  }

  //

  case class Mapped[I, T](wrapped: EndpointInput[I], f: I => T, g: T => I, paramsAsArgs: ParamsAsArgs[I]) extends Single[T] {
    override def show: String = s"map(${wrapped.show})"
  }

  //

  case class Multiple[I](inputs: Vector[Single[_]]) extends EndpointInput[I] {
    override def and[J, IJ](other: EndpointInput[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): EndpointInput.Multiple[IJ] =
      other match {
        case s: Single[_]           => Multiple(inputs :+ s)
        case Multiple(m)            => Multiple(inputs ++ m)
        case EndpointIO.Multiple(m) => Multiple(inputs ++ m)
      }
    def show: String = if (inputs.isEmpty) "-" else inputs.map(_.show).mkString(" ")
  }
}

sealed trait EndpointIO[I] extends EndpointInput[I] {
  def and[J, IJ](other: EndpointIO[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): EndpointIO[IJ]

  def show: String
  override def map[II](f: I => II)(g: II => I)(implicit paramsAsArgs: ParamsAsArgs[I]): EndpointIO[II] =
    EndpointIO.Mapped(this, f, g, paramsAsArgs)

  override def mapTo[COMPANION, CASE_CLASS <: Product](c: COMPANION)(implicit fc: FnComponents[COMPANION, I, CASE_CLASS],
                                                                     paramsAsArgs: ParamsAsArgs[I]): EndpointIO[CASE_CLASS] = {
    map[CASE_CLASS](fc.tupled(c).apply)(ProductToParams(_, fc.arity).asInstanceOf[I])(paramsAsArgs)
  }

  private[tapir] override def asVectorOfSingle: Vector[EndpointIO.Single[_]] = this match {
    case s: EndpointIO.Single[_]   => Vector(s)
    case m: EndpointIO.Multiple[_] => m.ios
  }
}

object EndpointIO {
  sealed trait Single[I] extends EndpointIO[I] with EndpointInput.Single[I] {
    def and[J, IJ](other: EndpointIO[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): EndpointIO[IJ] =
      other match {
        case s: Single[_]      => Multiple(Vector(this, s))
        case Multiple(outputs) => Multiple(this +: outputs)
      }
  }

  sealed trait Basic[I] extends Single[I] with EndpointInput.Basic[I]

  case class Body[T, M <: MediaType, R](codec: CodecForOptional[T, M, R], info: Info[T]) extends Basic[T] {
    def description(d: String): Body[T, M, R] = copy(info = info.description(d))
    def example(t: T): Body[T, M, R] = copy(info = info.example(t))
    def show = s"{body as ${codec.meta.mediaType.mediaType}}"
  }

  case class StreamBodyWrapper[S, M <: MediaType](wrapped: StreamingEndpointIO.Body[S, M]) extends Basic[S] {
    def show = s"{body as stream, ${wrapped.mediaType.mediaType}}"
  }

  case class Header[T](name: String, codec: PlainCodecForMany[T], info: Info[T]) extends Basic[T] {
    def description(d: String): Header[T] = copy(info = info.description(d))
    def example(t: T): Header[T] = copy(info = info.example(t))
    def show = s"{header $name}"
  }

  case class Headers(info: Info[Seq[(String, String)]]) extends Basic[Seq[(String, String)]] {
    def description(d: String): Headers = copy(info = info.description(d))
    def example(t: Seq[(String, String)]): Headers = copy(info = info.example(t))
    def show = s"{multiple headers}"
  }

  //

  case class Mapped[I, T](wrapped: EndpointIO[I], f: I => T, g: T => I, paramsAsArgs: ParamsAsArgs[I]) extends Single[T] {
    override def show: String = s"map(${wrapped.show})"
  }

  //

  case class Multiple[I](ios: Vector[Single[_]]) extends EndpointIO[I] {
    override def and[J, IJ](other: EndpointInput[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): EndpointInput.Multiple[IJ] =
      other match {
        case s: EndpointInput.Single[_] => EndpointInput.Multiple((ios: Vector[EndpointInput.Single[_]]) :+ s)
        case EndpointInput.Multiple(m)  => EndpointInput.Multiple((ios: Vector[EndpointInput.Single[_]]) ++ m)
        case EndpointIO.Multiple(m)     => EndpointInput.Multiple((ios: Vector[EndpointInput.Single[_]]) ++ m)
      }
    override def and[J, IJ](other: EndpointIO[J])(implicit ts: ParamConcat.Aux[I, J, IJ]): Multiple[IJ] =
      other match {
        case s: Single[_] => Multiple(ios :+ s)
        case Multiple(m)  => Multiple(ios ++ m)
      }
    def show: String = if (ios.isEmpty) "-" else ios.map(_.show).mkString(" ")
  }

  //

  case class Info[T](description: Option[String], example: Option[T]) {
    def description(d: String): Info[T] = copy(description = Some(d))
    def example(t: T): Info[T] = copy(example = Some(t))
  }
  object Info {
    def empty[T]: Info[T] = Info[T](None, None)
  }
}

/*
Streaming body is a special kind of input, as it influences the 4th type parameter of `Endpoint`. Other inputs
(`EndpointInput`s and `EndpointIO`s aren't parametrised with the type of streams that they use (to make them simpler),
so we need to pass the streaming information directly between the streaming body input and the endpoint.

That's why the streaming body input is a separate trait, unrelated to `EndpointInput`: it can't be combined with
other inputs, and the `Endpoint.in(EndpointInput)` method can't be used to add a streaming body. Instead, there's an
overloaded variant `Endpoint.in(StreamingEndpointIO)`, which takes into account the streaming type.

Internally, the streaming body is converted into a wrapper `EndpointIO`, which "forgets" about the streaming
information. The `EndpointIO.StreamBodyWrapper` should only be used internally, not by the end user: there's no
factory method in `Tapir` which would directly create an instance of it.
 */
sealed trait StreamingEndpointIO[I, +S] {
  def map[II](f: I => II)(g: II => I)(implicit paramsAsArgs: ParamsAsArgs[I]): StreamingEndpointIO[II, S] =
    StreamingEndpointIO.Mapped(this, f, g, paramsAsArgs)

  private[tapir] def toEndpointIO: EndpointIO[I]
}

object StreamingEndpointIO {
  case class Body[S, M <: MediaType](schema: Schema, mediaType: M, info: EndpointIO.Info[String]) extends StreamingEndpointIO[S, S] {
    def description(d: String): Body[S, M] = copy(info = info.description(d))
    def example(t: String): Body[S, M] = copy(info = info.example(t))

    private[tapir] override def toEndpointIO: EndpointIO.StreamBodyWrapper[S, M] = EndpointIO.StreamBodyWrapper(this)
  }

  case class Mapped[I, T, S](wrapped: StreamingEndpointIO[I, S], f: I => T, g: T => I, paramsAsArgs: ParamsAsArgs[I])
      extends StreamingEndpointIO[T, S] {
    private[tapir] override def toEndpointIO: EndpointIO[T] = wrapped.toEndpointIO.map(f)(g)
  }
}
