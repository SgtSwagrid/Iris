package com.alecdorrington.iris

/**
  * The thought a thinking model should give a request, from least to most. Less
  * is quicker and cheaper, and leaves more of the token limit for the answer;
  * more may answer a hard request better. A model that cannot be told refuses
  * the request with [[LlmError.Unsuccessful]].
  *
  * OpenAI treats [[Max]] as [[High]]; Gemini is sent a thinking-token budget.
  */
enum Effort:

  /** As little thought as the model gives. */
  case Low

  /** Some thought. */
  case Medium

  /** Thorough thought. */
  case High

  /** As much thought as the model gives. */
  case Max
