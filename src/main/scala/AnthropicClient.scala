package com.alecdorrington.iris

import cats.MonadThrow
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[LlmClient]] adapter for the [Anthropic Messages
  * API](https://docs.anthropic.com/en/api/messages).
  */
private[iris] final class AnthropicClient[F[_] : MonadThrow]
  (config: LlmConfig, backend: Backend[F])
  extends LlmClient[F]:

  override def send(chat: Chat, options: CompletionOptions): F[Completion] =
    JsonHttp.send[F, AnthropicClient.Response](backend, "Anthropic")(
      basicRequest
        .post(AnthropicClient.endpoint(config))
        .header("x-api-key", config.apiKey)
        .header("anthropic-version", "2023-06-01")
        .body(AnthropicClient.requestJson(config, chat, options))
        .contentType("application/json"),
    )(_.completion)

private[iris] object AnthropicClient:

  /** The URL for creating a message. */
  private def endpoint(config: LlmConfig): Uri = Uri.unsafeParse(s"${ config
      .origin("https://api.anthropic.com") }/v1/messages")

  /** Serialises a chat into an Anthropic request body. */
  def requestJson
    (
      config: LlmConfig,
      chat: Chat,
      options: CompletionOptions,
    )
    : String = Json
    .obj(
      "model"          -> options.model.getOrElse(config.model).asJson,
      "max_tokens"     -> options.maxTokens.getOrElse(config.maxTokens).asJson,
      "temperature"    -> options.temperature.asJson,
      "top_p"          -> options.topP.asJson,
      "stop_sequences" -> JsonHttp.stopSequences(options),
      "system"         -> chat.system.asJson,
      "messages"       -> chat.messages.map(message).asJson,
    )
    .deepDropNullValues
    .noSpaces

  /** Serialises a single chat message. */
  private def message(message: Message): Json = Json.obj(
    "role"    -> message.role.wire.asJson,
    "content" -> message.content.asJson,
  )

  /** Normalises an Anthropic stop reason. */
  private val stopReason: Option[String] => StopReason = StopReason.normalise(
    "end_turn",
    "max_tokens",
    Some("stop_sequence"),
  )

  /** One content block of an Anthropic response. */
  final case class Block(`type`: String, text: Option[String]) derives Decoder

  /** The token counts of an Anthropic response. */
  final case class TokenCounts(input_tokens: Int, output_tokens: Int)
    derives Decoder

  /** The subset of an Anthropic response body that is of interest here. */
  final case class Response
    (
      content: List[Block],
      stop_reason: Option[String],
      usage: Option[TokenCounts],
    )
    derives Decoder:

    /** This response as a provider-agnostic completion. */
    def completion: Completion = Completion(
      text = content.flatMap(_.text).mkString,
      stopReason = stopReason(stop_reason),
      usage = usage.map(counts =>
        Usage(
          counts.input_tokens,
          counts.output_tokens,
        ),
      ),
    )
