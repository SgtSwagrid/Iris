package com.alecdorrington.iris

import cats.effect.Async
import fs2.Stream
import io.circe.Json
import scala.annotation.unused
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.Uri

/** A request for a reply delivered as a stream of events. */
private[iris] type SseRequest[F[_]] = StreamRequest[
  Either[String, Stream[F, Byte]],
  Fs2Streams[F],
]

/**
  * What every streaming adapter does alike: refuse a chat the provider would
  * not answer, ask for the reply as a stream of events, and read each event for
  * whatever it says about the reply so far.
  *
  * A subclass supplies only what its provider does differently.
  */
private[iris] abstract class SseClient[F[_] : Async]
  (
    config: LlmConfig,
    backend: StreamBackend[F, Fs2Streams[F]],
  )
  extends LlmStream[F]:

  /** The provider whose API this adapter speaks. */
  protected def provider: LlmProvider

  /** The URL which streams a reply to a request made with these options. */
  protected def endpoint(options: CompletionOptions): Either[LlmError, Uri]

  /** The body of a request asking for this chat to be answered in pieces. */
  protected def body(chat: Chat, options: CompletionOptions): String

  /** The given request, bearing this provider's credentials. */
  protected def authenticated(request: SseRequest[F]): SseRequest[F]

  /**
    * What one event says about the reply, where it says anything. A list,
    * because a provider may end a reply in the same breath as finishing it.
    */
  protected def deltas(event: Json): List[Delta]

  /** Why this provider would refuse the chat, where it would. */
  protected def acceptable
    (
      chat: Chat,
      @unused
      options: CompletionOptions,
    )
    : Either[LlmError, Unit] = JsonHttp.answerable(provider, chat)

  override def stream
    (chat: Chat, options: CompletionOptions)
    : Stream[F, Delta] = Stream
    .eval(Async[F].fromEither(request(chat, options)))
    .flatMap(sent)
    .through(Sse.events)
    .map(deltas)
    .flatMap(Stream.emits)

  /** The request which asks for the next reply, or why it cannot be made. */
  private def request
    (chat: Chat, options: CompletionOptions)
    : Either[LlmError, SseRequest[F]] =
    for
      _   <- acceptable(chat, options)
      url <- endpoint(options)
    yield authenticated(
      basicRequest
        .post(url)
        .body(body(chat, options))
        .contentType("application/json")
        .readTimeout(config.timeout)
        .response(asStreamUnsafe(Fs2Streams[F])),
    )

  /** The bytes of a response, or its failure raised into the stream. */
  private def sent(request: SseRequest[F]): Stream[F, Byte] = Stream
    .eval(request.send(backend))
    .flatMap: response =>
      response
        .body
        .fold(
          error =>
            Stream.raiseError[F](LlmError.Http(
              provider.displayName,
              response.code,
              JsonHttp.retryAfter(response.header("Retry-After")),
              error,
            )),
          identity,
        )
