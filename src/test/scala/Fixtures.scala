package com.alecdorrington.iris

import io.circe.Json
import io.circe.parser.parse

/** What the suites share. */
object Fixtures:

  /** A configuration to build adapters from, sending nowhere real. */
  val config: LlmConfig = LlmConfig(
    LlmProvider.Anthropic,
    "key",
    "model-x",
    512,
  )

  /** Parses JSON that a test knows to be well formed. */
  def json(body: String): Json = parse(body).toOption.get
