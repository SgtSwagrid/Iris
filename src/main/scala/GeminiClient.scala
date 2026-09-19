package com.alecdorrington.iris

import cats.MonadThrow
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[LlmClient]] adapter for the [Google Gemini
  * API](https://ai.google.dev/api/generate-content).
  */
private[iris] final class GeminiClient[F[_] : MonadThrow]
  (config: LlmConfig, backend: Backend[F])
  extends LlmClient[F]:

  override def send(chat: Chat, options: CompletionOptions): F[Completion] =
    JsonHttp.send[F, GeminiClient.Response](backend, "Gemini")(
      basicRequest
        .post(GeminiClient.endpoint(
          config,
          options.model.getOrElse(config.model),
        ))
        .header("x-goog-api-key", config.apiKey)
        .body(GeminiClient.requestJson(config, chat, options))
        .contentType("application/json"),
    )(_.completion)

private[iris] object GeminiClient:

  /** The URL for generating content with the given model. */
  private def endpoint(config: LlmConfig, model: String): Uri =
    val base = config.origin("https://generativelanguage.googleapis.com")
    Uri.unsafeParse(s"$base/v1beta/models/$model:generateContent")

  /** Serialises a chat into a Gemini request body. */
  def requestJson
    (
      config: LlmConfig,
      chat: Chat,
      options: CompletionOptions,
    )
    : String = Json
    .obj(
      "system_instruction" ->
        chat
          .system
          .map(text => Json.obj("parts" -> Json.arr(part(text))))
          .asJson,
      "contents"         -> chat.messages.map(content).asJson,
      "generationConfig" -> Json.obj(
        "maxOutputTokens" ->
          options.maxTokens.getOrElse(config.maxTokens).asJson,
        "temperature"   -> options.temperature.asJson,
        "topP"          -> options.topP.asJson,
        "stopSequences" -> JsonHttp.stopSequences(options),
      ),
    )
    .deepDropNullValues
    .noSpaces

  /** Serialises a single chat message. */
  private def content(message: Message): Json = Json.obj(
    "role"  -> role(message.role).asJson,
    "parts" -> Json.arr(part(message.content)),
  )

  /** Serialises a single text part. */
  private def part(text: String): Json = Json.obj("text" -> text.asJson)

  /** The Gemini name for a message role. */
  private def role(role: Role): String = role match
    case Role.Assistant => "model"
    case other          => other.wire

  /** Normalises a Gemini finish reason. */
  private val stopReason: Option[String] => StopReason =
    StopReason.normalise("STOP", "MAX_TOKENS")

  /** One text part of a Gemini response. */
  final case class Part(text: Option[String]) derives Decoder

  /** The content of a Gemini candidate. */
  final case class Content(parts: Option[List[Part]]) derives Decoder

  /** One candidate reply of a Gemini response. */
  final case class Candidate
    (
      content: Option[Content],
      finishReason: Option[String],
    )
    derives Decoder

  /** The token counts of a Gemini response. */
  final case class TokenCounts
    (
      promptTokenCount: Option[Int],
      candidatesTokenCount: Option[Int],
    )
    derives Decoder

  /** The subset of a Gemini response body that is of interest here. */
  final case class Response
    (
      candidates: Option[List[Candidate]],
      usageMetadata: Option[TokenCounts],
    )
    derives Decoder:

    /** This response as a provider-agnostic completion. */
    def completion: Completion =
      val first = candidates.getOrElse(List.empty).headOption
      Completion(
        text = first
          .flatMap(_.content)
          .flatMap(_.parts)
          .getOrElse(List.empty)
          .flatMap(_.text)
          .mkString,
        stopReason = stopReason(first.flatMap(_.finishReason)),
        usage = usageMetadata.flatMap(counts =>
          for
            prompt    <- counts.promptTokenCount
            candidate <- counts.candidatesTokenCount
          yield Usage(prompt, candidate),
        ),
      )
