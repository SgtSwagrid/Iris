package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.Async
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[LlmClient]] adapter for the [Anthropic Messages
  * API](https://docs.anthropic.com/en/api/messages).
  */
private[iris] final class AnthropicClient[F[_] : MonadThrow]
  (config: LlmConfig, backend: Backend[F])
  extends JsonClient[F, AnthropicClient.Response](config, backend):

  override protected def provider: LlmProvider = LlmProvider.Anthropic

  override protected def endpoint
    (options: CompletionOptions)
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    provider,
    config.origin,
    "v1",
    "messages",
  )

  override protected def body(chat: Chat, options: CompletionOptions): String =
    AnthropicClient.requestJson(config, chat, options)

  override protected def authenticated
    (request: Request[Either[String, String]])
    : Request[Either[String, String]] = request
    .header("x-api-key", config.apiKey)
    .header("anthropic-version", "2023-06-01")

  override protected def completion
    (response: AnthropicClient.Response)
    : Either[LlmError, Completion] = response.completion

  /** Anthropic refuses an empty chat, and one it would have to prefill. */
  override protected def acceptable
    (chat: Chat, options: CompletionOptions)
    : Either[LlmError, Unit] =
    for
      _ <- JsonHttp.answerable(provider, chat)
      _ <- AnthropicClient.continuable(chat, model(options))
    yield ()

  override def count(chat: Chat, options: CompletionOptions): F[Int] =
    asking[AnthropicClient.TokenCount, Int](
      for
        _        <- JsonHttp.answerable(provider, chat)
        endpoint <- AnthropicClient.counting(config)
      yield authenticated(JsonHttp.post(
        endpoint,
        AnthropicClient.countJson(config, chat, options),
      )),
    )(count => Right(count.inputTokens))

  /** The model this request is for. */
  private def model(options: CompletionOptions): String = config
    .settings(options)
    .model

/**
  * An [[LlmStream]] adapter for the [Anthropic Messages
  * API](https://docs.anthropic.com/en/api/messages).
  */
private[iris] final class AnthropicStream[F[_] : Async]
  (
    config: LlmConfig,
    backend: StreamBackend[F, Fs2Streams[F]],
  )
  extends SseClient[F](config, backend):

  override protected def provider: LlmProvider = LlmProvider.Anthropic

  override protected def endpoint
    (options: CompletionOptions)
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    provider,
    config.origin,
    "v1",
    "messages",
  )

  override protected def body(chat: Chat, options: CompletionOptions): String =
    AnthropicClient.requestJson(config, chat, options, true)

  override protected def authenticated(request: SseRequest[F]): SseRequest[F] =
    request
      .header("x-api-key", config.apiKey)
      .header("anthropic-version", "2023-06-01")

  override protected def deltas(event: Json): List[Delta] = AnthropicClient
    .deltas(event)

  /** Anthropic refuses an empty chat, and one it would have to prefill. */
  override protected def acceptable
    (chat: Chat, options: CompletionOptions)
    : Either[LlmError, Unit] =
    for
      _ <- JsonHttp.answerable(provider, chat)
      _ <- AnthropicClient.continuable(chat, config.settings(options).model)
    yield ()

private[iris] object AnthropicClient:

  /**
    * The models which no longer accept sampling parameters. Anthropic removed
    * `temperature`, `top_p` and `top_k` from its newer models in favour of
    * thinking and effort, and they reject a request which carries them.
    */
  private val withoutSampling: Set[String] = Set(
    "claude-opus-5",
    "claude-opus-4-8",
    "claude-opus-4-7",
    "claude-sonnet-5",
    "claude-fable-5",
    "claude-fable-5-1",
    "claude-mythos-5",
    "claude-mythos-5-1",
  )

  /**
    * Whether the given model accepts sampling parameters. A model absent from
    * [[withoutSampling]] is assumed to, so that an unfamiliar one, be it a
    * proxy's or newer than this list, is left to speak for itself.
    */
  private def samples(model: String): Boolean = !withoutSampling.contains(model)

  /**
    * The models which no longer accept a prefilled reply, and so refuse a chat
    * which ends with the assistant's own message. Every model which dropped
    * sampling is one, along with the generation before it, which kept sampling.
    */
  private val withoutPrefill: Set[String] = withoutSampling ++
    Set("claude-opus-4-6", "claude-sonnet-4-6")

  /** Whether this chat asks the given model to continue its own reply. */
  private def prefills(chat: Chat, model: String): Boolean = withoutPrefill
    .contains(model) &&
    chat.messages.lastOption.exists(_.role == Role.Assistant)

  /** Refuses a chat which this model would not be willing to continue. */
  def continuable(chat: Chat, model: String): Either[LlmError, Unit] = Either
    .cond(
      !prefills(chat, model),
      (),
      LlmError.Unsendable(
        LlmProvider.Anthropic.displayName,
        s"$model will not continue an assistant message of its own",
      ),
    )

  /** The URL for counting the tokens of a message. */
  private def counting(config: LlmConfig): Either[LlmError, Uri] = JsonHttp
    .endpoint(
      LlmProvider.Anthropic,
      config.origin,
      "v1",
      "messages",
      "count_tokens",
    )

  /** Serialises a chat into a request to count it, which needs no limit. */
  def countJson
    (
      config: LlmConfig,
      chat: Chat,
      options: CompletionOptions,
    )
    : String = Json
    .obj(
      "model"    -> config.settings(options).model.asJson,
      "system"   -> chat.system.asJson,
      "messages" -> chat.messages.map(message).asJson,
    )
    .deepDropNullValues
    .noSpaces

  /** The token count of an Anthropic counting response. */
  final case class TokenCount(inputTokens: Int)

  object TokenCount:

    given Decoder[TokenCount] =
      Decoder.forProduct1("input_tokens")(TokenCount.apply)

  /** Serialises a chat into an Anthropic request body. */
  def requestJson
    (
      config: LlmConfig,
      chat: Chat,
      options: CompletionOptions,
      streaming: Boolean = false,
    )
    : String =
    val settings = config.settings(options)
    val sampling = samples(settings.model)
    Json
      .obj(
        "model"          -> settings.model.asJson,
        "max_tokens"     -> settings.maxTokens.asJson,
        "temperature"    -> options.temperature.filter(_ => sampling).asJson,
        "top_p"          -> options.topP.filter(_ => sampling).asJson,
        "stop_sequences" -> JsonHttp.stopSequences(options.stopSequences),
        "tools"          -> tools(options),
        "stream"         -> Option.when(streaming)(true).asJson,
        "system"         -> chat.system.asJson,
        "messages"       -> chat.messages.map(message).asJson,
      )
      .deepDropNullValues
      .noSpaces

  /**
    * Serialises a single chat message. A message of text alone is sent as a
    * bare string, which Anthropic takes as one block of text, so that carrying
    * media costs nothing to those who do not.
    */
  private def message(message: Message): Json = Json.obj(
    "role"    -> message.role.wire.asJson,
    "content" ->
      (if message.isText then message.text.asJson
       else message.content.map(part).asJson),
  )

  /** Serialises one part of a message. */
  private def part(part: Part): Json = part match
    case Part.Text(text) => Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      )
    case Part.Media(mediaType, data) => Json.obj(
        "type" ->
          (if mediaType.startsWith("image/") then "image" else "document")
            .asJson,
        "source" -> Json.obj(
          "type"       -> "base64".asJson,
          "media_type" -> mediaType.asJson,
          "data"       -> data.asJson,
        ),
      )
    case Part.ToolRequest(id, name, arguments) => Json.obj(
        "type"  -> "tool_use".asJson,
        "id"    -> id.asJson,
        "name"  -> name.asJson,
        "input" -> arguments,
      )
    case Part.ToolResult(id, _, content) => Json.obj(
        "type"        -> "tool_result".asJson,
        "tool_use_id" -> id.asJson,
        "content"     -> content.asJson,
      )

  /** Normalises an Anthropic stop reason. */
  private val stopReason: Option[String] => StopReason = StopReason.normalise(
    "end_turn",
    "max_tokens",
    Some("stop_sequence"),
    Some("tool_use"),
  )

  /** Serialises a tool on offer. */
  private def tool(tool: Tool): Json = Json.obj(
    "name"         -> tool.name.asJson,
    "description"  -> tool.description.asJson,
    "input_schema" -> tool.parameters,
  )

  /** Serialises the tools on offer, omitted when there are none. */
  private def tools(options: CompletionOptions): Json = Option
    .when(options.tools.nonEmpty)(options.tools.map(tool))
    .asJson

  /**
    * What one streamed event says. Text arrives as a delta to a content block;
    * the reason for stopping arrives with the message's own delta, whose usage
    * counts the output alone, the input having been counted at the start of the
    * stream.
    */
  def deltas(event: Json): List[Delta] =
    val cursor = event.hcursor
    cursor.get[String]("type").toOption match
      case Some("content_block_delta") => cursor
          .downField("delta")
          .get[String]("text")
          .toOption
          .map(Delta.Text.apply)
          .toList
      case Some("message_delta") => List(Delta.End(
          stopReason(
            cursor.downField("delta").get[String]("stop_reason").toOption,
          ),
          None,
        ))
      case _ => List.empty

  /** One content block of an Anthropic response. */
  final case class Block
    (
      kind: String,
      text: Option[String],
      id: Option[String],
      name: Option[String],
      input: Option[Json],
    ):

    /** What this block contributes to the reply, if it is one of text. */
    def reply: Option[String] = text.filter(_ => kind == "text")

    /** The tool this block asks for, if it asks for one. */
    def toolRequest: Option[Part.ToolRequest] =
      for
        _    <- Option.when(kind == "tool_use")(())
        id   <- id
        name <- name
      yield Part.ToolRequest(id, name, input.getOrElse(Json.obj()))

  object Block:

    given Decoder[Block] =
      Decoder.forProduct5("type", "text", "id", "name", "input")(Block.apply)

  /** The token counts of an Anthropic response. */
  final case class TokenCounts
    (
      inputTokens: Option[Int],
      outputTokens: Option[Int],
    )

  object TokenCounts:

    given Decoder[TokenCounts] =
      Decoder.forProduct2("input_tokens", "output_tokens")(TokenCounts.apply)

  /** The subset of an Anthropic response body that is of interest here. */
  final case class Response
    (
      content: List[Block],
      stopReason: Option[String],
      usage: Option[TokenCounts],
    ):

    /**
      * This response as a provider-agnostic completion. Content may be empty
      * without being wrong: a refusal says what it has to say in its stop
      * reason, so the stop reason is left to carry it.
      */
    def completion: Either[LlmError, Completion] = Right(Completion(
      text = content.flatMap(_.reply).mkString,
      stopReason = AnthropicClient.stopReason(stopReason),
      usage = usage.flatMap(c => Usage.of(c.inputTokens, c.outputTokens)),
      toolCalls = content.flatMap(_.toolRequest),
    ))

  object Response:

    given Decoder[Response] =
      Decoder.forProduct3("content", "stop_reason", "usage")(Response.apply)
