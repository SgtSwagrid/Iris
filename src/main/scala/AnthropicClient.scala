package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.Async
import fs2.Stream
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.{Header, Uri}

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
    : Either[LlmError, Uri] = AnthropicClient.endpoint(config)

  override protected def body(chat: Chat, options: CompletionOptions): String =
    AnthropicClient.requestJson(config, chat, options)

  override protected def authenticated
    (request: Request[Either[String, String]])
    : Request[Either[String, String]] =
    request.headers(AnthropicClient.credentials(config)*)

  override protected def completion
    (response: AnthropicClient.Response)
    : Either[LlmError, Completion] = response.completion

  override protected def acceptable
    (chat: Chat, options: CompletionOptions)
    : Either[LlmError, Unit] = AnthropicClient.acceptable(config, chat, options)

  /**
    * Anthropic writes a prefix without replying at all when allowed no tokens
    * to reply with, which is what it offers for warming.
    */
  override def warm(chat: Chat, options: CompletionOptions): F[Completion] =
    send(
      chat.cacheable,
      options.copy(maxTokens = Some(0)),
    )

  override def count(chat: Chat, options: CompletionOptions): F[Int] =
    asking[AnthropicClient.TokenCount, Int](
      JsonHttp
        .answerable(provider, chat)
        .flatMap(_ => AnthropicClient.counting(config)),
      AnthropicClient.countJson(config, chat, options),
    )(count => Right(count.inputTokens))

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
    : Either[LlmError, Uri] = AnthropicClient.endpoint(config)

  override protected def body(chat: Chat, options: CompletionOptions): String =
    AnthropicClient.requestJson(config, chat, options, true)

  override protected def authenticated(request: SseRequest[F]): SseRequest[F] =
    request.headers(AnthropicClient.credentials(config)*)

  override protected def reply(events: Stream[F, Json]): Stream[F, Delta] = Sse
    .each(AnthropicClient.deltas)(events)

  override protected def acceptable
    (chat: Chat, options: CompletionOptions)
    : Either[LlmError, Unit] = AnthropicClient.acceptable(config, chat, options)

private[iris] object AnthropicClient:

  /** The URL for sending a message. */
  def endpoint(config: LlmConfig): Either[LlmError, Uri] = JsonHttp.endpoint(
    LlmProvider.Anthropic,
    config.origin,
    "v1",
    "messages",
  )

  /**
    * The headers which authenticate a request, and name the version of the API
    * it is written for.
    */
  def credentials(config: LlmConfig): Seq[Header] = Seq(
    Header("x-api-key", config.apiKey),
    Header("anthropic-version", "2023-06-01"),
  )

  /**
    * Anthropic refuses an empty chat, one the model it is for would have to
    * prefill, and one marking more blocks for caching than it keeps.
    */
  def acceptable
    (
      config: LlmConfig,
      chat: Chat,
      options: CompletionOptions,
    )
    : Either[LlmError, Unit] =
    for
      _ <- JsonHttp.answerable(LlmProvider.Anthropic, chat)
      _ <- continuable(chat, config.settings(options).model)
      _ <- breakable(chat)
    yield ()

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

  /** The most blocks Anthropic keeps marked for caching in one request. */
  val maxBreakpoints = 4

  /** Refuses a chat marking more blocks for caching than Anthropic keeps. */
  def breakable(chat: Chat): Either[LlmError, Unit] =
    val count = marks(chat)
    Either.cond(
      count <= maxBreakpoints,
      (),
      LlmError.Unsendable(
        LlmProvider.Anthropic.displayName,
        s"its cache breakpoints mark $count blocks, and at most $maxBreakpoints are kept",
      ),
    )

  /**
    * How many blocks a chat's breakpoints mark, as it is sent: breakpoints in a
    * row mark the one block before them, and one with nothing before it marks
    * nothing at all.
    */
  private def marks(chat: Chat): Int =
    val (cached, blocks) = placed(chat)
    val onSystem         = if cached && chat.system.nonEmpty then 1 else 0
    onSystem + blocks.flatten.count(_._2)

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
    : String =
    val (system, messages) = conversation(chat)
    JsonHttp.requestBody(
      "model"    -> config.settings(options).model.asJson,
      "system"   -> system,
      "messages" -> messages,
    )

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
    val settings           = config.settings(options)
    val sampling           = samples(settings.model)
    val (system, messages) = conversation(chat)
    JsonHttp.requestBody(
      "model"          -> settings.model.asJson,
      "max_tokens"     -> settings.maxTokens.asJson,
      "temperature"    -> options.temperature.filter(_ => sampling).asJson,
      "top_p"          -> options.topP.filter(_ => sampling).asJson,
      "stop_sequences" -> JsonHttp.unlessEmpty(options.stopSequences),
      "tools"          -> JsonHttp.unlessEmpty(options.tools.map(tool)),
      "stream"         -> Option.when(streaming)(true).asJson,
      "system"         -> system,
      "messages"       -> messages,
    )

  /**
    * One part of a message as it is sent, and whether a breakpoint marks it as
    * the last of a prefix to cache.
    */
  private type Marked = (Part, Boolean)

  /**
    * Whether a breakpoint marks the system message, and every message's blocks
    * so far.
    */
  private type Placed = (Boolean, Vector[Vector[Marked]])

  /**
    * A chat's system message and messages, serialised with each breakpoint
    * placed on the block just before it.
    */
  private def conversation(chat: Chat): (Json, Json) =
    val (cached, blocks) = placed(chat)
    (
      chat.system.map(system(_, cached)).asJson,
      chat
        .messages
        .zip(blocks)
        .map((message, blocks) => this.message(message.role, blocks))
        .asJson,
    )

  /** Every message's blocks, each breakpoint placed on the block before it. */
  private def placed(chat: Chat): Placed = chat
    .messages
    .foldLeft((false, Vector.empty[Vector[Marked]])):
      case ((cached, blocks), message) => message
          .content
          .foldLeft((cached, blocks :+ Vector.empty[Marked]))(place)

  /**
    * The blocks so far, with one more part of the last message placed. A
    * breakpoint marks the block before it in its message, or failing that the
    * last of an earlier message, or failing that the system message itself.
    */
  private def place(placed: Placed, part: Part): Placed =
    val (cached, blocks) = placed
    part match
      case Part.CacheBreakpoint => blocks.lastIndexWhere(_.nonEmpty) match
          case -1 => (true, blocks)
          case at => (cached, blocks.updated(at, markLast(blocks(at))))
      case _ => (cached, blocks.init :+ (blocks.last :+ (part -> false)))

  /** The given blocks, the last of them marked. */
  private def markLast(blocks: Vector[Marked]): Vector[Marked] = blocks.init :+
    (blocks.last._1 -> true)

  /** Serialises a system message, as a block when a breakpoint marks it. */
  private def system(text: String, cached: Boolean): Json =
    if cached then Json.arr(marked(Part.Text(text) -> true)) else text.asJson

  /**
    * Serialises a single chat message. A message of text alone, marking no
    * prefix, is sent as a bare string, which Anthropic takes as one block of
    * text, so that carrying media or caching costs nothing to those who do not.
    */
  private def message(role: Role, blocks: Vector[Marked]): Json = Json.obj(
    "role"    -> role.wire.asJson,
    "content" ->
      (if blocks.forall((part, cached) =>
           !cached && part.isInstanceOf[Part.Text],
         )
       then
         blocks
           .collect:
             case (Part.Text(text), _) => text
           .mkString
           .asJson
       else blocks.map(marked).asJson),
  )

  /** Serialises one block, marked as the end of a prefix to cache if it is. */
  private def marked(block: Marked): Json =
    val (content, cached) = block
    if cached then
      part(content).deepMerge(Json.obj(
        "cache_control" -> Json.obj("type" -> "ephemeral".asJson),
      ))
    else part(content)

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
    // A breakpoint is sent as a mark on the block before it, never as a block
    // of its own, and so is taken out before any part is serialised.
    case Part.CacheBreakpoint => Json.obj()

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

  /**
    * The token counts of an Anthropic response. Anthropic counts as input only
    * what it neither wrote to its cache nor read from it, and counts those two
    * apart.
    */
  final case class TokenCounts
    (
      inputTokens: Option[Int],
      outputTokens: Option[Int],
      cacheWrites: Option[Int],
      cacheReads: Option[Int],
    ):

    /** These counts as usage, every token of the prompt counted as input. */
    def usage: Option[Usage] = Usage.of(
      inputTokens.map(_ + cacheWrites.getOrElse(0) + cacheReads.getOrElse(0)),
      outputTokens,
      cacheReads,
    )

  object TokenCounts:

    given Decoder[TokenCounts] = Decoder.forProduct4(
      "input_tokens",
      "output_tokens",
      "cache_creation_input_tokens",
      "cache_read_input_tokens",
    )(TokenCounts.apply)

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
      usage = usage.flatMap(_.usage),
      toolCalls = content.flatMap(_.toolRequest),
    ))

  object Response:

    given Decoder[Response] =
      Decoder.forProduct3("content", "stop_reason", "usage")(Response.apply)
