package com.alecdorrington.iris

/**
  * The model's reply to a [[Chat]] or [[Prompt]].
  *
  * @param text
  *   The text of the reply.
  *
  * @param stopReason
  *   The reason the model stopped generating.
  *
  * @param usage
  *   The token counts for the request, where the provider reports them.
  *
  * @param toolCalls
  *   The tools the model asked to have run before it can go on. Empty unless
  *   tools were offered and the model wanted one.
  */
final case class Completion
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Option[Usage] = None,
    toolCalls: List[Part.ToolRequest] = List.empty,
  )

/** The reason a model stopped generating, normalised across providers. */
enum StopReason:

  /** The model finished its reply naturally. */
  case Completed

  /** The reply was truncated upon reaching the token limit. */
  case MaxTokens

  /** The reply ended upon producing a configured stop sequence. */
  case StopSequence

  /** The model stopped to await the result of a tool it asked for. */
  case ToolUse

  /** A provider-specific reason not covered by the other cases. */
  case Other(reason: String)

  /** The provider did not say why the model stopped. */
  case Unknown

object StopReason:

  /**
    * Normalises a provider-specific stop reason, given that provider's labels
    * for the [[Completed]] and [[MaxTokens]] cases, and optionally for
    * [[StopSequence]] and [[ToolUse]]. An unrecognised label is preserved as
    * [[Other]], and a reason the provider did not give at all is [[Unknown]].
    */
  private[iris] def normalise
    (
      completed: String,
      maxTokens: String,
      stopSequence: Option[String] = None,
      toolUse: Option[String] = None,
    )
    (reason: Option[String])
    : StopReason = reason match
    case Some(`completed`)                           => Completed
    case Some(`maxTokens`)                           => MaxTokens
    case Some(other) if stopSequence.contains(other) => StopSequence
    case Some(other) if toolUse.contains(other)      => ToolUse
    case Some(other)                                 => Other(other)
    case None                                        => Unknown

/**
  * The token counts for one request, as reported by the provider.
  *
  * @param inputTokens
  *   The number of tokens in the request, including the entire chat history.
  *
  * @param outputTokens
  *   The number of tokens in the model's reply.
  */
final case class Usage(inputTokens: Int, outputTokens: Int)

object Usage:

  /**
    * The usage a provider reported, where it reported both counts. Providers
    * omit them, individually or altogether, on a reply they did not give, so
    * neither is required of them.
    */
  private[iris] def of(input: Option[Int], output: Option[Int]): Option[Usage] =
    for
      inputTokens  <- input
      outputTokens <- output
    yield Usage(inputTokens, outputTokens)
