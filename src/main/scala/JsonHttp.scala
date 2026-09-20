package com.alecdorrington.iris

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import sttp.client4.*
import sttp.model.Uri

/** Shared HTTP plumbing for the JSON APIs of all providers. */
private[iris] object JsonHttp:

  /**
    * Sends a JSON request, decodes the provider's response body as `R`, and
    * extracts an `A` from it. Failed requests and malformed responses are
    * raised as [[LlmError]]s.
    *
    * @param backend
    *   The HTTP backend to send the request over.
    *
    * @param provider
    *   The provider being spoken to, named in any error.
    *
    * @param timeout
    *   How long to wait for the response before giving up.
    *
    * @param request
    *   The request to send, with body and headers already applied, or why it
    *   could not be made.
    *
    * @param extract
    *   Converts a decoded response into what was asked for, or fails when it
    *   carries nothing to convert.
    *
    * @return
    *   An effect producing the extracted value.
    */
  def send[F[_] : MonadThrow, R : Decoder, A]
    (
      backend: Backend[F],
      provider: LlmProvider,
      timeout: FiniteDuration,
    )
    (
      request: Either[
        LlmError,
        Request[Either[String, String]],
      ],
    )
    (extract: R => Either[LlmError, A])
    : F[A] = MonadThrow[F]
    .fromEither(request)
    .flatMap(_.readTimeout(timeout).send(backend))
    .flatMap: response =>
      MonadThrow[F].fromEither(parse[R](provider, response).flatMap(extract))

  /**
    * Resolves an API endpoint beneath the given origin. The origin may come
    * from a host's own configuration, so one which is not a URL fails here
    * rather than throwing, and the path is encoded rather than interpolated.
    */
  def endpoint
    (
      provider: LlmProvider,
      origin: String,
      path: String*,
    )
    : Either[LlmError, Uri] = Uri
    .parse(origin)
    .bimap(
      LlmError.Misconfigured(provider.displayName, _),
      _.addPath(path),
    )

  /** A JSON request posting the given body to the given endpoint. */
  def post(endpoint: Uri, body: String): Request[Either[String, String]] =
    basicRequest.post(endpoint).body(body).contentType("application/json")

  /**
    * Refuses a chat with nothing in it to answer, which every provider rejects,
    * before a request is spent discovering as much.
    */
  def answerable(provider: LlmProvider, chat: Chat): Either[LlmError, Unit] =
    Either.cond(
      chat.messages.nonEmpty,
      (),
      LlmError.Unsendable(
        provider.displayName,
        "it has no messages",
      ),
    )

  /** Serialises stop sequences for a request body, omitted when empty. */
  def stopSequences(sequences: List[String]): Json = Option
    .when(sequences.nonEmpty)(sequences)
    .asJson

  /**
    * How long a provider asked us to wait, where it said so in seconds. The
    * header may instead name a date, which is left unread: a host which cares
    * that much can read the header itself, and one which does not is better off
    * with nothing than with a number this got wrong.
    */
  private[iris] def retryAfter(header: Option[String]): Option[FiniteDuration] =
    header.map(_.trim).flatMap(_.toIntOption).filter(_ >= 0).map(_.seconds)

  /** Decodes a response body, mapping failures to [[LlmError]]s. */
  private def parse[R : Decoder]
    (
      provider: LlmProvider,
      response: Response[Either[String, String]],
    )
    : Either[LlmError, R] = response
    .body
    .leftMap(LlmError.Http(
      provider.displayName,
      response.code,
      retryAfter(response.header("Retry-After")),
      _,
    ))
    .flatMap: body =>
      decode[R](body).leftMap: error =>
        LlmError.Malformed(provider.displayName, error.getMessage)
