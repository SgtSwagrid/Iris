package com.alecdorrington.iris

/**
  * The set of supported LLM providers.
  *
  * @param defaultModel
  *   The model used for this provider when none is configured explicitly.
  */
enum LlmProvider(val defaultModel: String):

  case Anthropic extends LlmProvider("claude-sonnet-5")
  case OpenAi    extends LlmProvider("gpt-5")
  case Gemini    extends LlmProvider("gemini-2.5-flash")

object LlmProvider:

  /** Parses a provider from its name, case-insensitively. */
  def parse(name: String): Option[LlmProvider] = name.trim.toLowerCase match
    case "anthropic"         => Some(Anthropic)
    case "openai"            => Some(OpenAi)
    case "gemini" | "google" => Some(Gemini)
    case _                   => None

/**
  * Configuration for connecting to an LLM provider.
  *
  * @param provider
  *   The provider whose API is to be used.
  *
  * @param apiKey
  *   The API key used to authenticate with the provider.
  *
  * @param model
  *   The name of the model to prompt, unless overridden per request.
  *
  * @param maxTokens
  *   The maximum number of tokens permitted in each completion, unless
  *   overridden per request.
  *
  * @param baseUrl
  *   Overrides the provider's API origin (scheme and host, with no trailing
  *   `/`), e.g. to reach a proxy, a compatible third-party endpoint, or a stub
  *   in tests. The provider's own origin is used when unset.
  */
final case class LlmConfig
  (
    provider: LlmProvider,
    apiKey: String,
    model: String,
    maxTokens: Int,
    baseUrl: Option[String] = None,
  ):

  /** The API origin to send to: [[baseUrl]] when set, else the given one. */
  def origin(default: String): String = baseUrl.getOrElse(default)

  /** Describes this configuration without its [[apiKey]], so it is safe to log. */
  override def toString: String =
    s"LlmConfig($provider, <redacted>, $model, $maxTokens, $baseUrl)"

object LlmConfig:

  /** The environment variables that may hold provider API keys. */
  val apiKeyVariables: List[String] = LlmProvider
    .values
    .toList
    .flatMap(keyNames)

  /** The optional configuration environment variables. */
  val optionVariables: List[String] = List(
    "LLM_PROVIDER",
    "LLM_MODEL",
    "LLM_MAX_TOKENS",
    "LLM_BASE_URL",
  )

  /**
    * Loads configuration from environment variables:
    *   - `LLM_PROVIDER`: `anthropic`, `openai` or `gemini`. When absent, the
    *     provider is inferred from whichever API key is set. When present but
    *     unrecognised, no configuration is produced (rather than silently
    *     routing requests to an unintended provider).
    *   - `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY` (or
    *     `GOOGLE_API_KEY`): the provider API key.
    *   - `LLM_MODEL`: overrides the provider's default model.
    *   - `LLM_MAX_TOKENS`: the completion token limit (default `8192`).
    *   - `LLM_BASE_URL`: overrides the provider's API origin.
    *
    * @return
    *   A configuration, or `None` when no provider API key is set.
    */
  def fromEnv: Option[LlmConfig] =
    for
      provider <- env("LLM_PROVIDER").fold(inferProvider)(LlmProvider.parse)
      apiKey   <- apiKey(provider)
    yield LlmConfig(
      provider = provider,
      apiKey = apiKey,
      model = env("LLM_MODEL").getOrElse(provider.defaultModel),
      maxTokens = env("LLM_MAX_TOKENS").flatMap(_.toIntOption).getOrElse(8192),
      baseUrl = env("LLM_BASE_URL"),
    )

  /** Infers the provider from whichever API key is present. */
  private def inferProvider: Option[LlmProvider] = LlmProvider
    .values
    .find(apiKey(_).isDefined)

  /** The first configured API key for a provider, if any. */
  private def apiKey(provider: LlmProvider): Option[String] = keyNames(provider)
    .collectFirst(Function.unlift(env))

  /** The environment variables that may hold a provider's API key. */
  private def keyNames(provider: LlmProvider): List[String] = provider match
    case LlmProvider.Anthropic => List("ANTHROPIC_API_KEY")
    case LlmProvider.OpenAi    => List("OPENAI_API_KEY")
    case LlmProvider.Gemini    => List("GEMINI_API_KEY", "GOOGLE_API_KEY")

  /**
    * Retrieves the value of an environment variable, if it exists and is
    * non-empty.
    */
  private def env(name: String): Option[String] = sys
    .env
    .get(name)
    .filter(_.nonEmpty)
