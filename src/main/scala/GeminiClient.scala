package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.Async
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[LlmClient]] adapter for the [Google Gemini
  * API](https://ai.google.dev/api/generate-content).
  */
private[iris] final class GeminiClient[F[_] : MonadThrow]
  (config: LlmConfig, backend: Backend[F])
  extends JsonClient[F, GeminiClient.Response](config, backend):

  override protected def provider: LlmProvider = LlmProvider.Gemini

  override protected def endpoint
    (options: CompletionOptions)
    : Either[LlmError, Uri] =
    GeminiClient.endpoint(config, config.settings(options).model)

  override protected def body(chat: Chat, options: CompletionOptions): String =
    GeminiClient.requestJson(config, chat, options)

  override protected def authenticated
    (request: Request[Either[String, String]])
    : Request[Either[String, String]] =
    request.header("x-goog-api-key", config.apiKey)

  override protected def completion
    (response: GeminiClient.Response)
    : Either[LlmError, Completion] = response.completion

  override def count(chat: Chat, options: CompletionOptions): F[Int] =
    asking[GeminiClient.TokenCount, Int](
      for
        _        <- JsonHttp.answerable(provider, chat)
        endpoint <-
          GeminiClient.counting(config, config.settings(options).model)
      yield authenticated(JsonHttp.post(endpoint, GeminiClient.countJson(chat))),
    )(count => Right(count.totalTokens))

/**
  * An [[LlmStream]] adapter for the [Google Gemini
  * API](https://ai.google.dev/api/generate-content).
  */
private[iris] final class GeminiStream[F[_] : Async]
  (
    config: LlmConfig,
    backend: StreamBackend[F, Fs2Streams[F]],
  )
  extends SseClient[F](config, backend):

  override protected def provider: LlmProvider = LlmProvider.Gemini

  override protected def endpoint
    (options: CompletionOptions)
    : Either[LlmError, Uri] =
    GeminiClient.streaming(config, config.settings(options).model)

  override protected def body(chat: Chat, options: CompletionOptions): String =
    GeminiClient.requestJson(config, chat, options)

  override protected def authenticated(request: SseRequest[F]): SseRequest[F] =
    request.header("x-goog-api-key", config.apiKey)

  override protected def deltas(event: Json): List[Delta] =
    GeminiClient.deltas(event)

private[iris] object GeminiClient:

  /**
    * The URL for generating content with the given model. The model names a
    * path segment, so it is encoded rather than interpolated, lest a name
    * bearing a `/` or a `?` address something else entirely.
    */
  def endpoint(config: LlmConfig, model: String): Either[LlmError, Uri] =
    JsonHttp.endpoint(
      LlmProvider.Gemini,
      config.origin,
      "v1beta",
      "models",
      s"$model:generateContent",
    )

  /** The URL for streaming a reply from the given model. */
  def streaming(config: LlmConfig, model: String): Either[LlmError, Uri] =
    JsonHttp
      .endpoint(
        LlmProvider.Gemini,
        config.origin,
        "v1beta",
        "models",
        s"$model:streamGenerateContent",
      )
      .map(_.addParam("alt", "sse"))

  /** The URL for counting the tokens of a request to the given model. */
  private def counting
    (config: LlmConfig, model: String)
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    LlmProvider.Gemini,
    config.origin,
    "v1beta",
    "models",
    s"$model:countTokens",
  )

  /** Serialises a chat into a request to count it. */
  def countJson(chat: Chat): String = Json
    .obj(
      "contents" ->
        (chat.system.map(systemContent).toList ++ chat.messages.map(content))
          .asJson,
    )
    .noSpaces

  /**
    * A system message as a counted content. Counting takes no
    * `system_instruction` of its own, so it is counted as a message would be.
    */
  private def systemContent(text: String): Json = Json.obj(
    "role"  -> "user".asJson,
    "parts" -> Json.arr(part(text)),
  )

  /** The token count of a Gemini counting response. */
  final case class TokenCount(totalTokens: Int) derives Decoder

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
      "tools"            -> tools(options),
      "contents"         -> chat.messages.map(content).asJson,
      "generationConfig" -> Json.obj(
        "maxOutputTokens" -> config.settings(options).maxTokens.asJson,
        "temperature"     -> options.temperature.asJson,
        "topP"            -> options.topP.asJson,
        "stopSequences"   -> JsonHttp.stopSequences(options.stopSequences),
      ),
    )
    .deepDropNullValues
    .noSpaces

  /**
    * Serialises a single chat message. Gemini caches long prefixes of its own
    * accord, so breakpoints say nothing to it.
    */
  private def content(message: Message): Json = Json.obj(
    "role"  -> role(message.role).asJson,
    "parts" -> message.uncached.content.map(part).asJson,
  )

  /** Serialises a single text part. */
  private def part(text: String): Json = Json.obj("text" -> text.asJson)

  /**
    * What one streamed event says. Gemini sends the same shape it sends whole,
    * a piece at a time, so a single event may both say something and be the
    * last to do so.
    */
  def deltas(event: Json): List[Delta] =
    val candidate = event.hcursor.downField("candidates").downN(0)
    val said      = candidate
      .downField("content")
      .get[List[ReplyPart]]("parts")
      .toOption
      .getOrElse(List.empty)
      .flatMap(_.text)
      .mkString
    List.concat(
      Option.when(said.nonEmpty)(Delta.Text(said)),
      candidate
        .get[String]("finishReason")
        .toOption
        .map(reason =>
          Delta.End(
            stopReason(Some(reason)),
            event
              .hcursor
              .get[TokenCounts]("usageMetadata")
              .toOption
              .flatMap(_.usage),
          ),
        ),
    )

  /** Serialises a tool on offer. */
  private def tool(tool: Tool): Json = Json.obj(
    "name"        -> tool.name.asJson,
    "description" -> tool.description.asJson,
    "parameters"  -> tool.parameters,
  )

  /**
    * Serialises the tools on offer, omitted when there are none. Gemini takes
    * them declared together, under one entry, rather than one entry apiece.
    */
  private def tools(options: CompletionOptions): Json = Option
    .when(options.tools.nonEmpty)(List(
      Json.obj("functionDeclarations" -> options.tools.map(tool).asJson),
    ))
    .asJson

  /** Serialises one part of a message. */
  private def part(content: Part): Json = content match
    case Part.Text(text)             => part(text)
    case Part.Media(mediaType, data) => Json.obj(
        "inline_data" -> Json.obj(
          "mime_type" -> mediaType.asJson,
          "data"      -> data.asJson,
        ),
      )
    case Part.ToolRequest(_, name, arguments) => Json.obj(
        "functionCall" -> Json.obj("name" -> name.asJson, "args" -> arguments),
      )
    case Part.ToolResult(_, name, content) => Json.obj(
        "functionResponse" -> Json.obj(
          "name"     -> name.asJson,
          "response" -> Json.obj("result" -> content.asJson),
        ),
      )
    // Breakpoints are taken out of a message before its parts are serialised.
    case Part.CacheBreakpoint => Json.obj()

  /** The Gemini name for a message role. */
  private def role(role: Role): String = role match
    case Role.Assistant => "model"
    case other          => other.wire

  /** Normalises a Gemini finish reason. */
  private val stopReason: Option[String] => StopReason =
    StopReason.normalise("STOP", "MAX_TOKENS")

  /**
    * A tool a Gemini reply asks for. Gemini names no identifier for one, so the
    * tool's own name stands in where a [[Part.ToolRequest.id]] is wanted.
    */
  final case class FunctionCall
    (name: String, args: Option[Json])
    derives Decoder:

    /** This call as a provider-agnostic request. */
    def toolRequest: Part.ToolRequest =
      Part.ToolRequest(name, name, args.getOrElse(Json.obj()))

  /** One part of a Gemini reply. */
  final case class ReplyPart
    (
      text: Option[String],
      functionCall: Option[FunctionCall],
    )
    derives Decoder

  /** The content of a Gemini candidate. */
  final case class Content(parts: Option[List[ReplyPart]]) derives Decoder

  /** One candidate reply of a Gemini response. */
  final case class Candidate
    (
      content: Option[Content],
      finishReason: Option[String],
    )
    derives Decoder

  /** What Gemini says about a prompt it declined to answer. */
  final case class Feedback(blockReason: Option[String]) derives Decoder

  /** Every part of a candidate's content. */
  private def parts(candidate: Candidate): List[ReplyPart] = candidate
    .content
    .flatMap(_.parts)
    .getOrElse(List.empty)

  /** The text of a candidate, across every part of its content. */
  private def text(candidate: Candidate): String = parts(candidate)
    .flatMap(_.text)
    .mkString

  /**
    * The tools a candidate asks for. Gemini reports no distinct finish reason
    * for having asked, so their presence is what says so.
    */
  private def toolRequests(candidate: Candidate): List[Part.ToolRequest] =
    parts(candidate).flatMap(_.functionCall).map(_.toolRequest)

  /**
    * The token counts of a Gemini response, whose prompt tokens include those
    * read from its cache.
    */
  final case class TokenCounts
    (
      promptTokenCount: Option[Int],
      candidatesTokenCount: Option[Int],
      cachedContentTokenCount: Option[Int],
    )
    derives Decoder:

    /** These counts as usage. */
    def usage: Option[Usage] = Usage.of(
      promptTokenCount,
      candidatesTokenCount,
      cachedContentTokenCount,
    )

  /** The subset of a Gemini response body that is of interest here. */
  final case class Response
    (
      candidates: Option[List[Candidate]],
      promptFeedback: Option[Feedback],
      usageMetadata: Option[TokenCounts],
    )
    derives Decoder:

    /**
      * This response as a provider-agnostic completion, or a failure when it
      * carries no candidate. A blocked prompt is answered this way, with a
      * `200` and no reply, so it would otherwise pass for an empty one.
      */
    def completion: Either[LlmError, Completion] = candidates
      .getOrElse(List.empty)
      .headOption
      .toRight(LlmError.Malformed(
        LlmProvider.Gemini.displayName,
        unanswered,
      ))
      .map: candidate =>
        val requested = toolRequests(candidate)
        Completion(
          text = text(candidate),
          stopReason =
            if requested.nonEmpty then StopReason.ToolUse
            else stopReason(candidate.finishReason),
          usage = usageMetadata.flatMap(_.usage),
          toolCalls = requested,
        )

    /** Why this response carried no candidate, as far as it says. */
    private def unanswered: String = promptFeedback
      .flatMap(_.blockReason)
      .fold("no candidates")(reason => s"no candidates, blocked as $reason")
