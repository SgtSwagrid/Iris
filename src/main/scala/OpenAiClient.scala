package com.alecdorrington.iris

import cats.MonadThrow
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[LlmClient]] adapter for the [OpenAI Chat Completions
  * API](https://platform.openai.com/docs/api-reference/chat).
  */
private[iris] final class OpenAiClient[F[_] : MonadThrow]
  (config: LlmConfig, backend: Backend[F])
  extends LlmClient[F]:

  override def send(chat: Chat, options: CompletionOptions): F[Completion] =
    JsonHttp.send[F, OpenAiClient.Response](backend, "OpenAI")(
      basicRequest
        .post(OpenAiClient.endpoint(config))
        .auth
        .bearer(config.apiKey)
        .body(OpenAiClient.requestJson(config, chat, options))
        .contentType("application/json"),
    )(_.completion)

private[iris] object OpenAiClient:

  /** The URL for creating a chat completion. */
  private def endpoint(config: LlmConfig): Uri = Uri.unsafeParse(s"${ config
      .origin("https://api.openai.com") }/v1/chat/completions")

  /** Serialises a chat into an OpenAI request body. */
  def requestJson
    (
      config: LlmConfig,
      chat: Chat,
      options: CompletionOptions,
    )
    : String =
    val messages = chat.system.map(message("system", _)).toList ++
      chat.messages.map(m => message(m.role.wire, m.content))
    Json
      .obj(
        "model"                 -> options.model.getOrElse(config.model).asJson,
        "max_completion_tokens" ->
          options.maxTokens.getOrElse(config.maxTokens).asJson,
        "temperature" -> options.temperature.asJson,
        "top_p"       -> options.topP.asJson,
        "stop"        -> JsonHttp.stopSequences(options),
        "messages"    -> messages.asJson,
      )
      .deepDropNullValues
      .noSpaces

  /** Serialises a single chat message. */
  private def message(role: String, content: String): Json = Json.obj(
    "role"    -> role.asJson,
    "content" -> content.asJson,
  )

  /** Normalises an OpenAI finish reason. */
  private val stopReason: Option[String] => StopReason =
    StopReason.normalise("stop", "length")

  /** One chat message of an OpenAI response. */
  final case class ReplyMessage(content: Option[String]) derives Decoder

  /** One choice of an OpenAI response. */
  final case class Choice
    (
      message: ReplyMessage,
      finish_reason: Option[String],
    )
    derives Decoder

  /** The token counts of an OpenAI response. */
  final case class TokenCounts
    (
      prompt_tokens: Int,
      completion_tokens: Int,
    )
    derives Decoder

  /** The subset of an OpenAI response body that is of interest here. */
  final case class Response
    (
      choices: List[Choice],
      usage: Option[TokenCounts],
    )
    derives Decoder:

    /** This response as a provider-agnostic completion. */
    def completion: Completion =
      val first = choices.headOption
      Completion(
        text = first.flatMap(_.message.content).getOrElse(""),
        stopReason = stopReason(first.flatMap(_.finish_reason)),
        usage = usage.map(counts =>
          Usage(
            counts.prompt_tokens,
            counts.completion_tokens,
          ),
        ),
      )
