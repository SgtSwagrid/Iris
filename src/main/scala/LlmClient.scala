package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.{Async, Resource}
import sttp.client4.Backend
import sttp.client4.httpclient.cats.HttpClientCatsBackend

/**
  * A provider-agnostic client for large language models. An adapter exists for
  * each supported [[LlmProvider]]; use [[LlmClient.resource]] or
  * [[LlmClient.fromEnv]] to construct one.
  *
  * Clients are stateless: providers are given no session identity, and nothing
  * is remembered between calls. Conversation history instead travels with each
  * [[Chat]], so continuing a conversation means appending to the chat and
  * sending the whole thing again.
  */
trait LlmClient[F[_]]:

  /**
    * Sends a chat to the model and returns its next reply.
    *
    * @param chat
    *   The full conversation so far, including any system message.
    *
    * @param options
    *   Optional per-request tuning; unset fields fall back to configured or
    *   provider defaults.
    *
    * @return
    *   An effect producing the model's reply.
    */
  def send
    (
      chat: Chat,
      options: CompletionOptions = CompletionOptions(),
    )
    : F[Completion]

  /** Sends a single-turn prompt to the model and returns its completion. */
  final def complete
    (
      prompt: Prompt,
      options: CompletionOptions = CompletionOptions(),
    )
    : F[Completion] = send(prompt.toChat, options)

object LlmClient:

  /** Creates a client for the given configuration over an existing backend. */
  def apply[F[_] : MonadThrow]
    (config: LlmConfig, backend: Backend[F])
    : LlmClient[F] = config.provider match
    case LlmProvider.Anthropic => AnthropicClient(config, backend)
    case LlmProvider.OpenAi    => OpenAiClient(config, backend)
    case LlmProvider.Gemini    => GeminiClient(config, backend)

  /** Creates a client for the given configuration, with its own HTTP backend. */
  def resource[F[_] : Async](config: LlmConfig): Resource[F, LlmClient[F]] =
    HttpClientCatsBackend.resource[F]().map(apply(config, _))

  /**
    * Creates a client configured from the environment, as specified by
    * [[LlmConfig.fromEnv]], or `None` when no provider is configured.
    */
  def fromEnv[F[_] : Async]: Resource[F, Option[LlmClient[F]]] = LlmConfig
    .fromEnv
    .fold(Resource.pure(None))(config => resource(config).map(Some(_)))
