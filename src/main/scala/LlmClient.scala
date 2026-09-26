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

  /**
    * Counts the tokens this chat would cost to send, as the provider counts
    * them, so that a host may check a conversation against a budget or a
    * context window before spending a completion on finding out.
    *
    * Not every provider offers this: OpenAI has no such endpoint, and fails
    * with [[LlmError.Unsupported]] rather than guessing with a tokeniser of its
    * own, which would be a different number confidently presented.
    *
    * @param chat
    *   The conversation to count, including any system message.
    *
    * @param options
    *   Optional per-request tuning; only the model is of any consequence.
    *
    * @return
    *   An effect producing the number of input tokens.
    */
  def count
    (
      chat: Chat,
      options: CompletionOptions = CompletionOptions(),
    )
    : F[Int]

  /** Counts the tokens a single-turn prompt would cost to send. */
  final def count(prompt: Prompt): F[Int] = count(prompt.toChat)

  /** Sends a single-turn prompt to the model and returns its completion. */
  final def complete
    (
      prompt: Prompt,
      options: CompletionOptions = CompletionOptions(),
    )
    : F[Completion] = send(prompt.toChat, options)

  /**
    * Writes the [[Chat.cacheable]] prefix of a chat into the provider's cache,
    * asking for as little reply as the provider allows, so that requests
    * sharing that prefix which are then sent at once all read it. Sent at once
    * unwarmed, none of them could read what the others were still writing, and
    * each would pay to write its own.
    *
    * Worth it only before several requests: one request writes the prefix just
    * as well by itself. The prefix must hold at least one message, and a chat
    * with no [[Part.CacheBreakpoint]] has none to warm, so it is refused as
    * [[LlmError.Unsendable]]. The model, tools and every other option must be
    * those the requests will be sent with, since a cache is kept per model.
    *
    * @return
    *   An effect producing the provider's empty reply, whose usage says how
    *   much of the prefix was cached.
    */
  def warm
    (
      chat: Chat,
      options: CompletionOptions = CompletionOptions(),
    )
    : F[Completion] = send(
    chat.cacheable,
    options.copy(maxTokens = Some(1)),
  )

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
