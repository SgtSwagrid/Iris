package com.alecdorrington.iris

/**
  * How able a model is wanted, and so how dear and how slow: every provider's
  * models ranked alike, so that a host may ask for a cheaper model for simple
  * work, or an abler one for hard work, without naming any one provider's. Each
  * tier is a model of its own, configured or else the provider's default for it
  * (see [[LlmProvider.model]]).
  */
enum ModelTier:

  /** A small model, quick and cheap, for simple and mechanical work. */
  case Fast

  /** The configured model, for most work. */
  case Standard

  /** The abler model, slower and dearer, for work needing judgement. */
  case Thorough
