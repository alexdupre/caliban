package caliban.interop.tapir

import caliban._
import caliban.interop.tapir.TapirAdapter._
import sttp.capabilities.Streams
import sttp.model.{ headers => _, _ }
import sttp.monad.MonadError
import sttp.shared.Identity
import sttp.tapir._
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.ServerEndpoint
import zio._

import scala.concurrent.Future

sealed trait HttpInterpreter[-R, E] { self =>
  protected def endpoints[S](streams: Streams[S]): List[
    PublicEndpoint[(GraphQLRequest, ServerRequest), TapirResponse, CalibanResponse[streams.BinaryStream], S]
  ]

  protected def executeRequest[BS](
    graphQLRequest: GraphQLRequest,
    serverRequest: ServerRequest
  )(implicit streamConstructor: StreamConstructor[BS]): ZIO[R, TapirResponse, CalibanResponse[BS]]

  def serverEndpoints[R1 <: R, S](streams: Streams[S])(implicit
    streamConstructor: StreamConstructor[streams.BinaryStream]
  ): List[CalibanEndpoint[R1, streams.BinaryStream, S]] = {
    def logic(
      request: (GraphQLRequest, ServerRequest)
    ): RIO[R1, Either[TapirResponse, CalibanResponse[streams.BinaryStream]]] = {
      val (graphQLRequest, serverRequest) = request
      executeRequest(graphQLRequest, serverRequest).either
    }
    endpoints[S](streams).map(_.serverLogic(logic(_)))
  }

  def serverEndpointsFuture[S](streams: Streams[S])(runtime: Runtime[R])(implicit
    streamConstructor: StreamConstructor[streams.BinaryStream]
  ): List[ServerEndpoint[S, Future]] = {
    implicit val r: Runtime[R] = runtime
    serverEndpoints(streams).map(
      convertHttpEndpointToFuture[R, streams.BinaryStream, S, (GraphQLRequest, ServerRequest)]
    )
  }

  def serverEndpointsIdentity[S](streams: Streams[S])(runtime: Runtime[R])(implicit
    streamConstructor: StreamConstructor[streams.BinaryStream]
  ): List[ServerEndpoint[S, Identity]] = {
    implicit val r: Runtime[R] = runtime
    serverEndpoints(streams).map(
      convertHttpEndpointToIdentity[R, streams.BinaryStream, S, (GraphQLRequest, ServerRequest)]
    )
  }

  def intercept[R1](interceptor: Interceptor[R1, R]): HttpInterpreter[R1, E] =
    HttpInterpreter.Intercepted(self, interceptor)

  def prependPath(path: List[String]): HttpInterpreter[R, E] =
    HttpInterpreter.Prepended(self, path)

  def configure[R1](configurator: Configurator[R1]): HttpInterpreter[R & R1, E] =
    intercept[R & R1](ZLayer.scopedEnvironment[R & R1 & ServerRequest](configurator *> ZIO.environment[R]))
}

object HttpInterpreter {
  private case class Base[R, E](interpreter: GraphQLInterpreter[R, E]) extends HttpInterpreter[R, E] {
    def endpoints[S](
      streams: Streams[S]
    ): List[PublicEndpoint[(GraphQLRequest, ServerRequest), TapirResponse, CalibanResponse[streams.BinaryStream], S]] =
      makeHttpEndpoints(streams)

    def executeRequest[BS](
      graphQLRequest: GraphQLRequest,
      serverRequest: ServerRequest
    )(implicit streamConstructor: StreamConstructor[BS]): ZIO[R, TapirResponse, CalibanResponse[BS]] = {
      val req = if (serverRequest.method == Method.GET) graphQLRequest.asHttpGetRequest else graphQLRequest
      interpreter.executeRequest(req).map(buildHttpResponse[E, BS](serverRequest))
    }
  }

  private case class Prepended[R, E](
    interpreter: HttpInterpreter[R, E],
    path: List[String]
  ) extends HttpInterpreter[R, E] {
    override def endpoints[S](
      streams: Streams[S]
    ): List[
      PublicEndpoint[(GraphQLRequest, ServerRequest), TapirResponse, CalibanResponse[streams.BinaryStream], S]
    ] = {
      val endpoints = interpreter.endpoints(streams)
      if (path.nonEmpty) {
        val p: List[EndpointInput[Unit]]   = path.map(stringToPath)
        val fixedPath: EndpointInput[Unit] = p.tail.foldLeft(p.head)(_ / _)

        endpoints.map(_.prependIn(fixedPath))
      } else {
        endpoints
      }
    }

    def executeRequest[BS](
      graphQLRequest: GraphQLRequest,
      serverRequest: ServerRequest
    )(implicit streamConstructor: StreamConstructor[BS]): ZIO[R, TapirResponse, CalibanResponse[BS]] =
      interpreter.executeRequest(graphQLRequest, serverRequest)
  }

  private case class Intercepted[R1, R, E](
    interpreter: HttpInterpreter[R, E],
    layer: ZLayer[R1 & ServerRequest, TapirResponse, R]
  ) extends HttpInterpreter[R1, E] {
    override def intercept[R2](interceptor: Interceptor[R2, R1]): HttpInterpreter[R2, E] =
      Intercepted[R2, R, E](interpreter, ZLayer.makeSome[R2 & ServerRequest, R](interceptor, layer))

    def endpoints[S](
      streams: Streams[S]
    ): List[PublicEndpoint[(GraphQLRequest, ServerRequest), TapirResponse, CalibanResponse[streams.BinaryStream], S]] =
      interpreter.endpoints(streams)

    def executeRequest[BS](
      graphQLRequest: GraphQLRequest,
      serverRequest: ServerRequest
    )(implicit streamConstructor: StreamConstructor[BS]): ZIO[R1, TapirResponse, CalibanResponse[BS]] =
      interpreter.executeRequest(graphQLRequest, serverRequest).provideSome[R1](ZLayer.succeed(serverRequest), layer)
  }

  def apply[R, E](interpreter: GraphQLInterpreter[R, E]): HttpInterpreter[R, E] =
    Base(interpreter)

  def makeHttpEndpoints[S](
    streams: Streams[S]
  ): List[PublicEndpoint[(GraphQLRequest, ServerRequest), TapirResponse, CalibanResponse[streams.BinaryStream], S]] = {
    def queryFromQueryParams(queryParams: QueryParams): DecodeResult[GraphQLRequest] =
      for {
        req <- JsonCodecs.requestCodec.decode(s"""{"query":"","variables":${queryParams
                   .get("variables")
                   .getOrElse("null")},"extensions":${queryParams
                   .get("extensions")
                   .getOrElse("null")}}""")

      } yield req.copy(query = queryParams.get("query"), operationName = queryParams.get("operationName"))

    def checkRequest(request: GraphQLRequest): DecodeResult[GraphQLRequest] =
      if (request.isEmpty) DecodeResult.Missing else DecodeResult.Value(request)

    val postEndpoint
      : PublicEndpoint[(GraphQLRequest, ServerRequest), TapirResponse, CalibanResponse[streams.BinaryStream], S] =
      endpoint.post
        .in(
          (headers and stringBody and queryParams).mapDecode { case (headers, body, params) =>
            val getRequest =
              if (params.get("query").isDefined)
                queryFromQueryParams(params)
              else if (
                headers.exists(header =>
                  header.name.equalsIgnoreCase(HeaderNames.ContentType) &&
                    MediaType
                      .parse(header.value)
                      .exists(mediaType => mediaType.mainType == "application" && mediaType.subType == "graphql")
                )
              )
                DecodeResult.Value(GraphQLRequest(query = Some(body)))
              else JsonCodecs.requestCodec.decode(body).flatMap(checkRequest)

            getRequest.map(request => headers.find(isFtv1Header).fold(request)(_ => request.withFederatedTracing))
          }(request => (Nil, JsonCodecs.requestCodec.encode(request), QueryParams()))
        )
        .in(extractFromRequest(identity))
        .out(header[MediaType](HeaderNames.ContentType))
        .out(statusCode)
        .out(header[Option[String]](HeaderNames.CacheControl))
        .out(outputBody(streams))
        .errorOut(errorBody)

    val getEndpoint
      : PublicEndpoint[(GraphQLRequest, ServerRequest), TapirResponse, CalibanResponse[streams.BinaryStream], S] =
      endpoint.get
        .in(
          queryParams.mapDecode(queryFromQueryParams(_).flatMap(checkRequest))(request =>
            QueryParams.fromMap(
              Map(
                "query"         -> request.query.getOrElse(""),
                "operationName" -> request.operationName.getOrElse(""),
                "variables"     -> request.variables
                  .map(_.map { case (k, v) => s""""$k":${v.toInputString}""" }.mkString("{", ",", "}"))
                  .getOrElse(""),
                "extensions"    -> request.extensions
                  .map(_.map { case (k, v) => s""""$k":${v.toInputString}""" }.mkString("{", ",", "}"))
                  .getOrElse("")
              ).filter { case (_, v) => v.nonEmpty }
            )
          )
        )
        .in(extractFromRequest(identity))
        .out(header[MediaType](HeaderNames.ContentType))
        .out(statusCode)
        .out(header[Option[String]](HeaderNames.CacheControl))
        .out(outputBody(streams))
        .errorOut(errorBody)

    postEndpoint :: getEndpoint :: Nil
  }

  /**
   * Creates an endpoint that serves the GraphiQL UI from CDN.
   *
   * @param apiPath The path at which the API can be introspected.
   *
   * @see [[https://github.com/graphql/graphiql/tree/main/examples/graphiql-cdn]]
   */
  def makeGraphiqlEndpoint[F[_]](
    apiPath: String
  )(implicit F: MonadError[F]): ServerEndpoint.Full[Unit, Unit, ServerRequest, Nothing, String, Any, F] =
    makeGraphiqlEndpointInner(apiPath, wsPath = None)

  /**
   * Creates an endpoint that serves the GraphiQL UI from CDN.
   *
   * @param apiPath The path at which the API can be introspected.
   * @param wsPath The path at which the WS subscription can be introspected.
   *
   * @see [[https://github.com/graphql/graphiql/tree/main/examples/graphiql-cdn]]
   */
  def makeGraphiqlEndpoint[F[_]](
    apiPath: String,
    wsPath: String
  )(implicit F: MonadError[F]): ServerEndpoint.Full[Unit, Unit, ServerRequest, Nothing, String, Any, F] =
    makeGraphiqlEndpointInner(apiPath, Some(wsPath))

  private def makeGraphiqlEndpointInner[F[_]](
    apiPath: String,
    wsPath: Option[String]
  )(implicit F: MonadError[F]): ServerEndpoint.Full[Unit, Unit, ServerRequest, Nothing, String, Any, F] = {
    val apiPath0 = apiPath.split("/").filter(_.nonEmpty).mkString("/", "/", "")
    infallibleEndpoint.get
      .in(extractFromRequest(identity))
      .out(htmlBodyUtf8)
      .serverLogic[F] { req =>
        val segments = req.pathSegments
        val uiPath   = segments.mkString("/", "/", "")
        val entity   = Right(HttpUtils.graphiqlHtml(apiPath = apiPath0, uiPath = uiPath, wsPath = wsPath))
        F.unit(entity)
      }
  }
}
