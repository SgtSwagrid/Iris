package com.alecdorrington.iris

import io.circe.Json
import io.circe.parser.{decode, parse}

object Fixtures:

  val config: LlmConfig = LlmConfig(LlmModel.ClaudeHaiku4_5, "key", 512)

  def json(body: String): Json = parse(body).toOption.get

  /** What a provider makes of a response body that a test knows it can read. */
  def answer(api: ProviderApi, body: String): Either[LlmError, Reply] =
    decode[Either[LlmError, Reply]](body)(using api.replies).toOption.get
