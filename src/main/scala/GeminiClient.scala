package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.Async
import fs2.Stream
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
      JsonHttp
        .answerable(provider, chat)
        .flatMap(_ =>
          GeminiClient.counting(config, config.settings(options).model),
        ),
      GeminiClient.countJson(chat),
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

  override protected def reply(events: Stream[F, Json]): Stream[F, Delta] =
    GeminiClient.reply(events)

private[iris] object GeminiClient:

  /**
    * The URL for calling the given method of the given model. The model names a
    * path segment, so it is encoded rather than interpolated, lest a name
    * bearing a `/` or a `?` address something else entirely.
    */
  private def calling
    (
      config: LlmConfig,
      model: String,
      method: String,
    )
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    LlmProvider.Gemini,
    config.origin,
    "v1beta",
    "models",
    s"$model:$method",
  )

  /** The URL for generating content with the given model. */
  def endpoint(config: LlmConfig, model: String): Either[LlmError, Uri] =
    calling(config, model, "generateContent")

  /** The URL for streaming a reply from the given model. */
  def streaming(config: LlmConfig, model: String): Either[LlmError, Uri] =
    calling(config, model, "streamGenerateContent").map(
      _.addParam("alt", "sse"),
    )

  /** The URL for counting the tokens of a request to the given model. */
  private def counting
    (config: LlmConfig, model: String)
    : Either[LlmError, Uri] = calling(config, model, "countTokens")

  /**
    * Serialises a chat into a request to count it, its contents as a request to
    * answer it sends them. Counting takes no `system_instruction` of its own,
    * so a system message is counted as the user's would be.
    */
  def countJson(chat: Chat): String = JsonHttp.requestBody(
    "contents" ->
      (chat.system.map(Message(Role.User, _)).toList ++ chat.messages)
        .map(content)
        .asJson,
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
    : String = JsonHttp.requestBody(
    "system_instruction" ->
      chat.system.map(text => Json.obj("parts" -> Json.arr(part(text)))).asJson,
    "tools"            -> tools(options),
    "contents"         -> chat.messages.map(content).asJson,
    "generationConfig" -> JsonHttp.obj(
      "maxOutputTokens" -> config.settings(options).maxTokens.asJson,
      "temperature"     -> options.temperature.asJson,
      "topP"            -> options.topP.asJson,
      "stopSequences"   -> JsonHttp.unlessEmpty(options.stopSequences),
    ),
  )

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
    * The reply which a stream's events make up. Gemini sends the same shape it
    * sends whole, a piece at a time, so a single event may both say something
    * and be the last to do so, and a reply which asked for a tool in any event
    * stopped for it, as a whole reply which asked for one does.
    */
  def reply[F[_]](events: Stream[F, Json]): Stream[F, Delta] = events
    .zipWithScan1(false)(_ || asks(_))
    .flatMap((event, asked) => Stream.emits(deltas(event, asked)))

  /** Whether one streamed event asks for a tool. */
  private def asks(event: Json): Boolean =
    streamed(event).exists(_.functionCall.nonEmpty)

  /** The parts of the reply which one streamed event carries. */
  private def streamed(event: Json): List[ReplyPart] = event
    .hcursor
    .downField("candidates")
    .downN(0)
    .downField("content")
    .get[List[ReplyPart]]("parts")
    .toOption
    .getOrElse(List.empty)

  /**
    * What one streamed event says, given whether the reply has asked for a tool
    * by the end of it.
    */
  private def deltas(event: Json, asked: Boolean): List[Delta] =
    val said = streamed(event).flatMap(_.text).mkString
    List.concat(
      Option.when(said.nonEmpty)(Delta.Text(said)),
      event
        .hcursor
        .downField("candidates")
        .downN(0)
        .get[String]("finishReason")
        .toOption
        .map(reason =>
          Delta.End(
            stopped(asked, Some(reason)),
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
    * Why a reply stopped, given whether it asked for a tool. Gemini reports no
    * distinct finish reason for having asked, so the asking is what says so.
    */
  private def stopped(asked: Boolean, reason: Option[String]): StopReason =
    if asked then StopReason.ToolUse else stopReason(reason)

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

  /** The tools a candidate asks for. */
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
      .flatMap(_.headOption)
      .toRight(LlmError.Malformed(
        LlmProvider.Gemini.displayName,
        unanswered,
      ))
      .map: candidate =>
        val requested = toolRequests(candidate)
        Completion(
          text = text(candidate),
          stopReason = stopped(
            requested.nonEmpty,
            candidate.finishReason,
          ),
          usage = usageMetadata.flatMap(_.usage),
          toolCalls = requested,
        )

    /** Why this response carried no candidate, as far as it says. */
    private def unanswered: String = promptFeedback
      .flatMap(_.blockReason)
      .fold("no candidates")(reason => s"no candidates, blocked as $reason")
