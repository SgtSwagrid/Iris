package com.alecdorrington.iris

import cats.effect.{Async, Resource}
import fs2.Stream
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.StreamBackend
import sttp.client4.httpclient.fs2.HttpClientFs2Backend

/**
  * A client which delivers a reply as it is written, rather than once it is
  * finished. Use it where a reader is waiting, and [[LlmClient]] where they are
  * not.
  *
  * Streaming is a capability apart from [[LlmClient]] because it needs a
  * backend which can stream, which not every backend can.
  */
trait LlmStream[F[_]]:

  /**
    * Sends a chat to the model and returns its reply as it arrives.
    *
    * @param chat
    *   The full conversation so far, including any system message.
    *
    * @param options
    *   Optional per-request tuning; unset fields fall back to configured or
    *   provider defaults.
    *
    * @return
    *   A stream of the reply's text, ending with why it ended. Nothing is sent
    *   until the stream is run, and a failure reaches it as an [[LlmError]]
    *   like any other.
    */
  def stream
    (
      chat: Chat,
      options: CompletionOptions = CompletionOptions(),
    )
    : Stream[F, Delta]

  /** Sends a single-turn prompt and returns its completion as it arrives. */
  final def stream(prompt: Prompt): Stream[F, Delta] = stream(prompt.toChat)

object LlmStream:

  /** Creates a streaming client over an existing streaming backend. */
  def apply[F[_] : Async]
    (
      config: LlmConfig,
      backend: StreamBackend[F, Fs2Streams[F]],
    )
    : LlmStream[F] = config.provider match
    case LlmProvider.Anthropic => AnthropicStream(config, backend)
    case LlmProvider.OpenAi    => OpenAiStream(config, backend)
    case LlmProvider.Gemini    => GeminiStream(config, backend)

  /** Creates a streaming client with a streaming backend of its own. */
  def resource[F[_] : Async](config: LlmConfig): Resource[F, LlmStream[F]] =
    HttpClientFs2Backend.resource[F]().map(apply(config, _))

  /**
    * Creates a streaming client configured from the environment, as specified
    * by [[LlmConfig.fromEnv]], or `None` when no provider is configured.
    */
  def fromEnv[F[_] : Async]: Resource[F, Option[LlmStream[F]]] = LlmConfig
    .fromEnv
    .fold(Resource.pure(None))(config => resource(config).map(Some(_)))
