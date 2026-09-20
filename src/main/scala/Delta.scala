package com.alecdorrington.iris

/**
  * A piece of a reply, as it arrives over a [[LlmStream]]. A reply is a run of
  * [[Text]] followed by one [[End]]; what a provider sends in between which
  * says nothing new is not reported.
  */
enum Delta:

  /** More of the reply's text, to be appended to what came before. */
  case Text(text: String)

  /**
    * The reply has ended, and nothing more will arrive.
    *
    * @param stopReason
    *   Why the model stopped, normalised as it is for a [[Completion]].
    *
    * @param usage
    *   The token counts, where the provider reports them as it ends. Anthropic
    *   counts the input at the start of a stream rather than the end, so the
    *   two halves never meet here; `send` remains the way to have both.
    */
  case End
    (
      stopReason: StopReason,
      usage: Option[Usage],
    )
