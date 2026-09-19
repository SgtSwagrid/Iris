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
  */
final case class Completion
  (
    text: String,
    stopReason: StopReason = StopReason.Completed,
    usage: Option[Usage] = None,
  )

/** The reason a model stopped generating, normalised across providers. */
enum StopReason:

  /** The model finished its reply naturally. */
  case Completed

  /** The reply was truncated upon reaching the token limit. */
  case MaxTokens

  /** The reply ended upon producing a configured stop sequence. */
  case StopSequence

  /** A provider-specific reason not covered by the other cases. */
  case Other(reason: String)

object StopReason:

  /**
    * Normalises a provider-specific stop reason, given that provider's labels
    * for the [[Completed]], [[MaxTokens]] and (optionally) [[StopSequence]]
    * cases. Unrecognised labels are preserved as [[Other]].
    */
  private[iris] def normalise
    (
      completed: String,
      truncated: String,
      stopped: Option[String] = None,
    )
    (reason: Option[String])
    : StopReason = reason match
    case Some(`completed`)                      => Completed
    case Some(`truncated`)                      => MaxTokens
    case Some(other) if stopped.contains(other) => StopSequence
    case Some(other)                            => Other(other)
    case None                                   => Other("unknown")

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
