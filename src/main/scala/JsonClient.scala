package com.alecdorrington.iris

import cats.MonadThrow
import io.circe.Decoder
import scala.annotation.unused
import sttp.client4.*
import sttp.model.Uri

/**
  * What every adapter for a provider's JSON API does alike: refuse a chat the
  * provider would not answer, address an endpoint, post a body to it under the
  * provider's own credentials, and read a completion back out of `R`.
  *
  * A subclass supplies only what its provider does differently.
  */
private[iris] abstract class JsonClient[F[_] : MonadThrow, R : Decoder]
  (config: LlmConfig, backend: Backend[F])
  extends LlmClient[F]:

  /** The provider whose API this adapter speaks. */
  protected def provider: LlmProvider

  /** The URL which answers a request made with these options. */
  protected def endpoint(options: CompletionOptions): Either[LlmError, Uri]

  /** The body of a request carrying this chat. */
  protected def body(chat: Chat, options: CompletionOptions): String

  /** The given request, bearing this provider's credentials. */
  protected def authenticated(request: Request[Either[String, String]])
    : Request[Either[String, String]]

  /** One of this provider's responses, as a provider-agnostic completion. */
  protected def completion(response: R): Either[LlmError, Completion]

  /**
    * Counting is refused by default, for the providers which offer no way to do
    * it. An adapter whose provider does overrides this.
    */
  override def count(chat: Chat, options: CompletionOptions): F[Int] =
    MonadThrow[F].raiseError(LlmError.Unsupported(
      provider.displayName,
      "counting tokens",
    ))

  /**
    * Sends a request of this provider's, reading an `A` out of a `B`. Used by
    * an adapter which asks its provider for something other than a reply.
    */
  protected def asking[B : Decoder, A]
    (
      request: Either[
        LlmError,
        Request[Either[String, String]],
      ],
    )
    (extract: B => Either[LlmError, A])
    : F[A] =
    JsonHttp.send[F, B, A](backend, provider, config.timeout)(request)(extract)

  /**
    * Why this provider would refuse the chat, where it would. Every provider
    * refuses one with no messages; an adapter may know of more.
    */
  protected def acceptable
    (
      chat: Chat,
      @unused
      options: CompletionOptions,
    )
    : Either[LlmError, Unit] = JsonHttp.answerable(provider, chat)

  override def send(chat: Chat, options: CompletionOptions): F[Completion] =
    asking[R, Completion](request(chat, options))(completion)

  /** The request which asks for the next reply, or why it cannot be made. */
  private def request
    (chat: Chat, options: CompletionOptions)
    : Either[
      LlmError,
      Request[Either[String, String]],
    ] =
    for
      _   <- acceptable(chat, options)
      url <- endpoint(options)
    yield authenticated(JsonHttp.post(url, body(chat, options)))
