package com.alecdorrington.iris

import munit.FunSuite
import scala.concurrent.duration.DurationInt

/** Tests of [[LlmConfig]] and the environment it is read from. */
class ConfigSuite extends FunSuite:

  test("a token limit is a positive whole number"):
    assertEquals(LlmConfig.tokenLimit("512"), Some(512))
    assertEquals(LlmConfig.tokenLimit("1"), Some(1))
    assertEquals(
      LlmConfig.tokenLimit(" 8192 "),
      Some(8192),
    )

  test("a token limit which is not a number is no limit at all"):
    assertEquals(LlmConfig.tokenLimit("8k"), None)
    assertEquals(LlmConfig.tokenLimit(""), None)
    assertEquals(LlmConfig.tokenLimit("8192.0"), None)

  test("a token limit must leave room for a reply"):
    assertEquals(LlmConfig.tokenLimit("0"), None)
    assertEquals(LlmConfig.tokenLimit("-1"), None)

  test("configurations never reveal their api key"):
    val secret = LlmConfig(
      LlmProvider.Anthropic,
      "sk-do-not-print",
      "model-x",
      512,
    )
    assert(!secret.toString.contains("sk-do-not-print"))

  test("a timeout is read as a positive number of seconds"):
    assertEquals(
      LlmConfig.seconds("30"),
      Some(30.seconds),
    )
    assertEquals(
      LlmConfig.seconds(" 600 "),
      Some(10.minutes),
    )

  test("a timeout which cannot be read is no timeout at all"):
    assertEquals(LlmConfig.seconds("30s"), None)
    assertEquals(LlmConfig.seconds("0"), None)
    assertEquals(LlmConfig.seconds("-5"), None)

  test("providers are parsed by name, case and space insensitively"):
    assertEquals(
      LlmProvider.parse(" Anthropic "),
      Some(LlmProvider.Anthropic),
    )
    assertEquals(
      LlmProvider.parse("OPENAI"),
      Some(LlmProvider.OpenAi),
    )
    assertEquals(
      LlmProvider.parse("google"),
      Some(LlmProvider.Gemini),
    )

  test("an unrecognised provider is no provider"):
    assertEquals(LlmProvider.parse("claude"), None)
    assertEquals(LlmProvider.parse(""), None)

  test("a tier prompts the model configured for it, else the provider's"):
    val config = LlmConfig(
      LlmProvider.Anthropic,
      "key",
      "model-x",
      512,
      fastModel = Some("model-fast"),
    )
    def model(options: CompletionOptions) = config.settings(options).model
    assertEquals(model(CompletionOptions()), "model-x")
    assertEquals(
      model(CompletionOptions(tier = Some(ModelTier.Standard))),
      "model-x",
    )
    assertEquals(
      model(CompletionOptions(tier = Some(ModelTier.Fast))),
      "model-fast",
    )
    assertEquals(
      model(CompletionOptions(tier = Some(ModelTier.Thorough))),
      LlmProvider.Anthropic.thoroughModel,
    )

  test("a model named outright outranks the tier asked for"):
    val config = LlmConfig(
      LlmProvider.Gemini,
      "key",
      "model-x",
      512,
    )
    assertEquals(
      config
        .settings(CompletionOptions(
          model = Some("model-y"),
          tier = Some(ModelTier.Fast),
        ))
        .model,
      "model-y",
    )
