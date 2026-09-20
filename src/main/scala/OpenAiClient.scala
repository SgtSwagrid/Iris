package com.alecdorrington.iris

import cats.MonadThrow
import cats.effect.Async
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import io.circe.syntax.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.client4.*
import sttp.model.Uri

/**
  * An [[LlmClient]] adapter for the [OpenAI Chat Completions
  * API](https://platform.openai.com/docs/api-reference/chat).
  */
private[iris] final class OpenAiClient[F[_] : MonadThrow]
  (config: LlmConfig, backend: Backend[F])
  extends JsonClient[F, OpenAiClient.Response](config, backend):

  override protected def provider: LlmProvider = LlmProvider.OpenAi

  override protected def endpoint
    (options: CompletionOptions)
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    provider,
    config.origin,
    "v1",
    "chat",
    "completions",
  )

  override protected def body(chat: Chat, options: CompletionOptions): String =
    OpenAiClient.requestJson(config, chat, options)

  override protected def authenticated
    (request: Request[Either[String, String]])
    : Request[Either[String, String]] = request.auth.bearer(config.apiKey)

  override protected def completion
    (response: OpenAiClient.Response)
    : Either[LlmError, Completion] = response.completion

  /** Chat completions take pictures, but no other media. */
  override protected def acceptable
    (chat: Chat, options: CompletionOptions)
    : Either[LlmError, Unit] =
    for
      _ <- JsonHttp.answerable(provider, chat)
      _ <- OpenAiClient.pictorial(chat)
    yield ()

/**
  * An [[LlmStream]] adapter for the [OpenAI Chat Completions
  * API](https://platform.openai.com/docs/api-reference/chat).
  */
private[iris] final class OpenAiStream[F[_] : Async]
  (
    config: LlmConfig,
    backend: StreamBackend[F, Fs2Streams[F]],
  )
  extends SseClient[F](config, backend):

  override protected def provider: LlmProvider = LlmProvider.OpenAi

  override protected def endpoint
    (options: CompletionOptions)
    : Either[LlmError, Uri] = JsonHttp.endpoint(
    provider,
    config.origin,
    "v1",
    "chat",
    "completions",
  )

  override protected def body(chat: Chat, options: CompletionOptions): String =
    OpenAiClient.requestJson(config, chat, options, true)

  override protected def authenticated(request: SseRequest[F]): SseRequest[F] =
    request.auth.bearer(config.apiKey)

  override protected def deltas(event: Json): List[Delta] =
    OpenAiClient.deltas(event)

  /** Chat completions take pictures, but no other media. */
  override protected def acceptable
    (chat: Chat, options: CompletionOptions)
    : Either[LlmError, Unit] =
    for
      _ <- JsonHttp.answerable(provider, chat)
      _ <- OpenAiClient.pictorial(chat)
    yield ()

private[iris] object OpenAiClient:

  /** Serialises a chat into an OpenAI request body. */
  def requestJson
    (
      config: LlmConfig,
      chat: Chat,
      options: CompletionOptions,
      streaming: Boolean = false,
    )
    : String =
    val settings = config.settings(options)
    val messages = chat.system.map(text("system", _)).toList ++
      chat.messages.flatMap(message)
    Json
      .obj(
        "model"                 -> settings.model.asJson,
        "max_completion_tokens" -> settings.maxTokens.asJson,
        "temperature"           -> options.temperature.asJson,
        "top_p"                 -> options.topP.asJson,
        "stop"   -> JsonHttp.stopSequences(options.stopSequences),
        "tools"  -> tools(options),
        "stream" -> Option.when(streaming)(true).asJson,
        // Usage is withheld from a stream unless it is asked for:
        "stream_options" ->
          Option
            .when(streaming)(Json.obj("include_usage" -> true.asJson))
            .asJson,
        "messages" -> messages.asJson,
      )
      .deepDropNullValues
      .noSpaces

  /**
    * The media which chat completions accept, which is pictures alone. A
    * document reaches OpenAI by another API entirely, so one here is refused
    * rather than quietly dropped or sent as something it is not.
    */
  def pictorial(chat: Chat): Either[LlmError, Unit] = chat
    .messages
    .flatMap(_.content)
    .collectFirst:
      case Part.Media(mediaType, _) if !mediaType.startsWith("image/") =>
        LlmError.Unsupported(
          LlmProvider.OpenAi.displayName,
          s"sending $mediaType in a chat",
        )
    .toLeft(())

  /** Serialises a message of text alone, under the given role. */
  private def text(role: String, content: String): Json = Json.obj(
    "role"    -> role.asJson,
    "content" -> content.asJson,
  )

  /**
    * Serialises a single chat message, as more than one where it carries tool
    * results: OpenAI takes each of those as a message of its own, under a role
    * of its own, rather than as part of what the user said.
    */
  private def message(message: Message): List[Json] =
    val spoken = message.content.filterNot(_.isInstanceOf[Part.ToolResult])
    message.toolResults.map(result) ++
      Option.when(spoken.nonEmpty)(said(message.role, spoken))

  /** Serialises a tool's result, which is a message in its own right here. */
  private def result(result: Part.ToolResult): Json = Json.obj(
    "role"         -> "tool".asJson,
    "tool_call_id" -> result.id.asJson,
    "content"      -> result.content.asJson,
  )

  /** Serialises what an author said, along with any tool they asked for. */
  private def said(role: Role, spoken: List[Part]): Json =
    val calls = spoken.collect:
      case request: Part.ToolRequest => request
    Json.obj(
      "role"    -> role.wire.asJson,
      "content" -> content(spoken.filterNot(_.isInstanceOf[Part.ToolRequest])),
      "tool_calls" -> Option.when(calls.nonEmpty)(calls.map(call)).asJson,
    )

  /**
    * Serialises the content of a message. One of text alone is sent as a bare
    * string, so that carrying media costs nothing to those who do not.
    */
  private def content(spoken: List[Part]): Json =
    if spoken.isEmpty then Json.Null
    else if spoken.forall(_.isInstanceOf[Part.Text]) then
      spoken
        .collect:
          case Part.Text(text) => text
        .mkString
        .asJson
    else spoken.flatMap(part).asJson

  /**
    * Serialises a tool the model asked for. Its arguments travel as a string of
    * JSON rather than as JSON, which is OpenAI's own peculiarity.
    */
  private def call(request: Part.ToolRequest): Json = Json.obj(
    "id"       -> request.id.asJson,
    "type"     -> "function".asJson,
    "function" -> Json.obj(
      "name"      -> request.name.asJson,
      "arguments" -> request.arguments.noSpaces.asJson,
    ),
  )

  /** Serialises a tool on offer. */
  private def tool(tool: Tool): Json = Json.obj(
    "type"     -> "function".asJson,
    "function" -> Json.obj(
      "name"        -> tool.name.asJson,
      "description" -> tool.description.asJson,
      "parameters"  -> tool.parameters,
    ),
  )

  /** Serialises the tools on offer, omitted when there are none. */
  private def tools(options: CompletionOptions): Json = Option
    .when(options.tools.nonEmpty)(options.tools.map(tool))
    .asJson

  /**
    * Serialises one part of what an author said, where it is something said. A
    * tool request is carried beside the content rather than within it, and a
    * tool result is a message of its own, so neither belongs here.
    */
  private def part(part: Part): Option[Json] = part match
    case Part.Text(text) => Some(Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      ))
    case Part.Media(mediaType, data) => Some(Json.obj(
        "type"      -> "image_url".asJson,
        "image_url" -> Json.obj("url" -> s"data:$mediaType;base64,$data".asJson),
      ))
    case _: Part.ToolRequest | _: Part.ToolResult => None

  /** Normalises an OpenAI finish reason. */
  private val stopReason: Option[String] => StopReason = StopReason.normalise(
    "stop",
    "length",
    toolUse = Some("tool_calls"),
  )

  /**
    * What one streamed event says. Text arrives as a delta to the choice; the
    * reason for stopping arrives on the choice itself, and the usage, having
    * been asked for, in the event which carries it.
    */
  def deltas(event: Json): List[Delta] =
    val choice = event.hcursor.downField("choices").downN(0)
    val said   = choice.downField("delta").get[String]("content").toOption
    val ended  = choice
      .get[String]("finishReason")
      .toOption
      .orElse(choice.get[String]("finish_reason").toOption)
    List.concat(
      said.filter(_.nonEmpty).map(Delta.Text.apply),
      ended.map(reason =>
        Delta.End(
          stopReason(Some(reason)),
          event
            .hcursor
            .get[TokenCounts]("usage")
            .toOption
            .flatMap(c => Usage.of(c.promptTokens, c.completionTokens)),
        ),
      ),
    )

  /** The tool an OpenAI reply asks for, and what to run it with. */
  final case class Function
    (name: String, arguments: Option[String])
    derives Decoder:

    /** The arguments, which arrive as a string of JSON rather than as JSON. */
    def parsed: Json = arguments
      .flatMap(parse(_).toOption)
      .getOrElse(Json.obj())

  /** One tool an OpenAI reply asks for. */
  final case class ToolCall(id: String, function: Function) derives Decoder:

    /** This call as a provider-agnostic request. */
    def toolRequest: Part.ToolRequest =
      Part.ToolRequest(id, function.name, function.parsed)

  /** One chat message of an OpenAI response. */
  final case class ReplyMessage
    (
      content: Option[String],
      toolCalls: Option[List[ToolCall]],
    )

  object ReplyMessage:

    given Decoder[ReplyMessage] =
      Decoder.forProduct2("content", "tool_calls")(ReplyMessage.apply)

  /** One choice of an OpenAI response. */
  final case class Choice
    (
      message: ReplyMessage,
      finishReason: Option[String],
    )

  object Choice:

    given Decoder[Choice] =
      Decoder.forProduct2("message", "finish_reason")(Choice.apply)

  /** The token counts of an OpenAI response. */
  final case class TokenCounts
    (
      promptTokens: Option[Int],
      completionTokens: Option[Int],
    )

  object TokenCounts:

    given Decoder[TokenCounts] =
      Decoder.forProduct2("prompt_tokens", "completion_tokens")(
        TokenCounts.apply,
      )

  /** The subset of an OpenAI response body that is of interest here. */
  final case class Response
    (
      choices: List[Choice],
      usage: Option[TokenCounts],
    )
    derives Decoder:

    /**
      * This response as a provider-agnostic completion, or a failure when it
      * offers no choice to draw one from.
      */
    def completion: Either[LlmError, Completion] = choices
      .headOption
      .toRight(LlmError.Malformed(
        LlmProvider.OpenAi.displayName,
        "no choices",
      ))
      .map: choice =>
        Completion(
          text = choice.message.content.getOrElse(""),
          stopReason = stopReason(choice.finishReason),
          usage =
            usage.flatMap(c => Usage.of(c.promptTokens, c.completionTokens)),
          toolCalls =
            choice.message.toolCalls.getOrElse(List.empty).map(_.toolRequest),
        )
