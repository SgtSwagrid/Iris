package com.alecdorrington.iris

/**
  * Optional per-request tuning. Unset fields fall back to the values in
  * [[LlmConfig]] or to the provider's own defaults.
  *
  * @param model
  *   Overrides the configured model for this request.
  *
  * @param maxTokens
  *   Overrides the configured completion token limit for this request.
  *
  * @param temperature
  *   The sampling temperature; higher values produce more varied replies.
  *
  * @param topP
  *   The nucleus sampling threshold, restricting sampling to the smallest set
  *   of tokens whose cumulative probability reaches this value.
  *
  * @param stopSequences
  *   Sequences at which the model stops generating, if produced.
  */
final case class CompletionOptions
  (
    model: Option[String] = None,
    maxTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stopSequences: List[String] = List.empty,
  )
