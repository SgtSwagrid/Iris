package com.alecdorrington.iris

import munit.FunSuite
import scala.concurrent.duration.DurationInt

class ConfigSuite extends FunSuite:

  test("a token limit is a positive whole number"):
    assertEquals(LlmConfig.positive("512"), Some(512))
    assertEquals(LlmConfig.positive("1"), Some(1))
    assertEquals(
      LlmConfig.positive(" 8192 "),
      Some(8192),
    )

  test("a token limit which is not a number is no limit at all"):
    assertEquals(LlmConfig.positive("8k"), None)
    assertEquals(LlmConfig.positive(""), None)
    assertEquals(LlmConfig.positive("8192.0"), None)

  test("a token limit must leave room for a reply"):
    assertEquals(LlmConfig.positive("0"), None)
    assertEquals(LlmConfig.positive("-1"), None)

  test("configurations never reveal their api key"):
    val secret = LlmConfig(
      LlmModel.ClaudeHaiku4_5,
      "sk-do-not-print",
      512,
    )
    assert(!secret.toString.contains("sk-do-not-print"))
    assert(secret.toString.contains("anthropic:claude-haiku-4-5"))

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

  test("a configuration can be read through any lookup"):
    val config = LlmConfig.from(
      Map(
        "OPENAI_API_KEY" -> "key",
        "LLM_MODEL"      -> "gpt-5-mini",
        "LLM_TIMEOUT"    -> "30",
      ).get,
    )
    assertEquals(
      config.map(_.provider),
      Some(LlmProvider.OpenAi),
    )
    assertEquals(
      config.map(_.model),
      Some(LlmModel.Gpt5Mini),
    )
    assertEquals(
      config.map(_.timeout),
      Some(30.seconds),
    )

  test("an unset token limit is the host's default, else Iris's own"):
    val keyed = Map("ANTHROPIC_API_KEY" -> "key")
    assertEquals(
      LlmConfig.from(keyed.get).map(_.maxTokens),
      Some(LlmConfig.defaultMaxTokens),
    )
    assertEquals(
      LlmConfig.from(keyed.get, defaultMaxTokens = 16_000).map(_.maxTokens),
      Some(16_000),
    )
    assertEquals(
      LlmConfig
        .from(
          (keyed + ("LLM_MAX_TOKENS" -> "500")).get,
          defaultMaxTokens = 16_000,
        )
        .map(_.maxTokens),
      Some(500),
    )

  test(
    "a lookup with no key, or naming an unknown provider, configures nothing",
  ):
    assertEquals(
      LlmConfig.from(Map.empty[String, String].get),
      None,
    )
    assertEquals(
      LlmConfig.from(
        Map(
          "ANTHROPIC_API_KEY" -> "key",
          "LLM_PROVIDER"      -> "claude",
        ).get,
      ),
      None,
    )

  test("models are parsed by their names in their providers' apis"):
    assertEquals(
      LlmModel.parse(" Claude-Opus-5-5 "),
      Some(LlmModel.ClaudeOpus5_5),
    )
    assertEquals(
      LlmModel.parse("gemini-2.5-flash-lite"),
      Some(LlmModel.Gemini2_5FlashLite),
    )
    LlmModel
      .listed
      .foreach(model => assertEquals(LlmModel.parse(model.id), Some(model)))

  test("an unrecognised model is no model"):
    assertEquals(
      LlmModel.parse("claude-sonet-5-5"),
      None,
    )
    assertEquals(
      LlmModel.parse("ClaudeSonnet5_5"),
      None,
    )
    assertEquals(LlmModel.parse(""), None)

  test("a model iris does not list is named with its provider"):
    assertEquals(
      LlmModel.parse("anthropic:claude-opus-6"),
      LlmModel.of(LlmProvider.Anthropic, "claude-opus-6"),
    )
    assertEquals(
      LlmModel.parse(" OpenAI:ft:gpt-5:team:x "),
      LlmModel.of(LlmProvider.OpenAi, "ft:gpt-5:team:x"),
    )

  test("a listed model named with its provider is the listed one"):
    assertEquals(
      LlmModel.parse("anthropic:claude-opus-5-5"),
      Some(LlmModel.ClaudeOpus5_5),
    )
    assertEquals(
      LlmModel.parse("openai:claude-opus-5-5"),
      None,
    )

  test("a model named by its provider and a listed id is the listed one"):
    assertEquals(
      LlmModel.of(
        LlmProvider.Anthropic,
        " Claude-Haiku-4-5 ",
      ),
      Some(LlmModel.ClaudeHaiku4_5),
    )
    assertEquals(
      LlmModel.of(LlmProvider.OpenAi, "claude-haiku-4-5"),
      None,
    )

  test("a blank name is no model"):
    assertEquals(
      LlmModel.of(LlmProvider.Anthropic, "  "),
      None,
    )
    assertEquals(
      LlmModel.of(LlmProvider.Gemini, "models/"),
      None,
    )

  test("a gemini model may be named after models/, as gemini names it"):
    assertEquals(
      LlmModel.parse("gemini:models/gemini-2.5-pro"),
      Some(LlmModel.Gemini2_5Pro),
    )
    assertEquals(
      LlmModel.parse("gemini:models/gemini-3-pro").map(_.id),
      Some("gemini-3-pro"),
    )
    assertEquals(
      LlmModel.parse("openai:models/gpt-6").map(_.id),
      Some("models/gpt-6"),
    )

  test("a model's full name is read back as that model"):
    assertEquals(
      LlmModel.ClaudeOpus5_5.name,
      "anthropic:claude-opus-5-5",
    )
    (LlmModel.listed ++ LlmModel.of(LlmProvider.OpenAi, "ft:gpt-5:x")).foreach(
      model =>
        assertEquals(
          LlmModel.parse(model.name),
          Some(model),
        ),
    )

  test("a name after no provider, or no name after one, is no model"):
    assertEquals(LlmModel.parse("claude:opus"), None)
    assertEquals(LlmModel.parse("anthropic: "), None)

  test("an unlisted model is read from the environment with its provider"):
    val config = LlmConfig.from(
      Map(
        "OPENAI_API_KEY" -> "key",
        "LLM_MODEL"      -> "openai:gpt-6",
      ).get,
    )
    assertEquals(
      config.map(_.model),
      LlmModel.of(LlmProvider.OpenAi, "gpt-6"),
    )

  test("each provider's default model is one it serves"):
    LlmProvider
      .values
      .foreach(provider =>
        assertEquals(
          provider.defaultModel.provider,
          provider,
        ),
      )

  test("a provider which is not named is the one serving the model named"):
    val config = LlmConfig.from(
      Map(
        "ANTHROPIC_API_KEY" -> "key",
        "OPENAI_API_KEY"    -> "key",
        "LLM_MODEL"         -> "gpt-5",
      ).get,
    )
    assertEquals(
      config.map(_.provider),
      Some(LlmProvider.OpenAi),
    )

  test(
    "a model which is unknown, or not the named provider's, configures nothing",
  ):
    assertEquals(
      LlmConfig.from(
        Map(
          "ANTHROPIC_API_KEY" -> "key",
          "LLM_MODEL"         -> "claude-sonet-5-5",
        ).get,
      ),
      None,
    )
    assertEquals(
      LlmConfig.from(
        Map(
          "ANTHROPIC_API_KEY" -> "key",
          "OPENAI_API_KEY"    -> "key",
          "LLM_PROVIDER"      -> "anthropic",
          "LLM_MODEL"         -> "gpt-5",
        ).get,
      ),
      None,
    )

  test("a token limit named for a request outranks the configured one"):
    val config = LlmConfig(LlmModel.Gemini2_5Flash, "key", 512)
    assertEquals(config.limit(ReplyOptions()), 512)
    assertEquals(
      config.limit(ReplyOptions(maxTokens = Some(64))),
      64,
    )
