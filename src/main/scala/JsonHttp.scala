package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import scala.concurrent.duration.DurationInt
import sttp.client4.*

/** Shared HTTP plumbing for the JSON APIs of all providers. */
private[iris] object JsonHttp:

  /**
    * How long to wait for a completion. Generous, because producing a long
    * completion can take a model several minutes.
    */
  private val timeout = 5.minutes

  /**
    * Sends a JSON request, decodes the provider's response body as `R`, and
    * extracts a completion from it. Failed requests and malformed responses are
    * raised as [[LlmError]]s.
    *
    * @param backend
    *   The HTTP backend to send the request over.
    *
    * @param providerName
    *   The name of the provider, used in error messages.
    *
    * @param request
    *   The request to send, with body and headers already applied.
    *
    * @param extract
    *   Converts a decoded response into a provider-agnostic completion.
    *
    * @return
    *   An effect producing the extracted completion.
    */
  def send[F[_] : MonadThrow, R : Decoder]
    (
      backend: Backend[F],
      providerName: String,
    )
    (request: Request[Either[String, String]])
    (extract: R => Completion)
    : F[Completion] = request
    .readTimeout(timeout)
    .send(backend)
    .flatMap: response =>
      MonadThrow[F].fromEither(parse[R](providerName, response).map(extract))

  /** Serialises stop sequences for a request body, omitted when empty. */
  def stopSequences(options: CompletionOptions): Json = Option
    .when(options.stopSequences.nonEmpty)(options.stopSequences)
    .asJson

  /** Decodes a response body, mapping failures to [[LlmError]]s. */
  private def parse[R : Decoder]
    (
      providerName: String,
      response: Response[Either[String, String]],
    )
    : Either[LlmError, R] = response
    .body
    .leftMap(error => LlmError.Http(providerName, response.code, error))
    .flatMap: body =>
      decode[R](body).leftMap: error =>
        LlmError.Malformed(providerName, error.getMessage)
