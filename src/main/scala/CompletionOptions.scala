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
  *   Omitted for the Anthropic models which no longer accept sampling.
  *
  * @param topP
  *   The nucleus sampling threshold, restricting sampling to the smallest set
  *   of tokens whose cumulative probability reaches this value. Omitted for the
  *   Anthropic models which no longer accept sampling.
  *
  * @param stopSequences
  *   Sequences at which the model stops generating, if produced. Empty means
  *   none, there being nothing an empty list could otherwise mean.
  *
  * @param tools
  *   The tools the model may ask to have run. Empty means none, and is sent as
  *   nothing at all rather than as an empty list.
  *
  * @param tier
  *   How able a model is wanted, when no [[model]] is named: the model
  *   configured for that tier is prompted (see [[LlmConfig.modelFor]]).
  */
final case class CompletionOptions
  (
    model: Option[String] = None,
    maxTokens: Option[Int] = None,
    temperature: Option[Double] = None,
    topP: Option[Double] = None,
    stopSequences: List[String] = List.empty,
    tools: List[Tool] = List.empty,
    tier: Option[ModelTier] = None,
  )

/**
  * What one request is actually made with, once the options given for it have
  * fallen back on the configuration behind them. Resolved once per request, by
  * [[LlmConfig.settings]], rather than at each of the places which ask.
  *
  * @param model
  *   The model to prompt.
  *
  * @param maxTokens
  *   The maximum number of tokens permitted in the completion.
  */
private[iris] final case class Settings(model: String, maxTokens: Int)
