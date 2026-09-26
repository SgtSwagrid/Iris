package com.alecdorrington.iris

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
  * The set of supported LLM providers, and what each one is. Everything here is
  * true of the provider rather than of one request to it, so an adapter need
  * not carry its own copy.
  *
  * @param displayName
  *   The provider's name as it is written, for display and in errors.
  *
  * @param origin
  *   The origin of the provider's API, used unless one is configured.
  *
  * @param defaultModel
  *   The model used for this provider when none is configured explicitly.
  *
  * @param fastModel
  *   The provider's model for [[ModelTier.Fast]] work, unless one is
  *   configured.
  *
  * @param thoroughModel
  *   The provider's model for [[ModelTier.Thorough]] work, unless one is
  *   configured.
  */
enum LlmProvider
  (
    val displayName: String,
    val origin: String,
    val defaultModel: String,
    val fastModel: String,
    val thoroughModel: String,
  ):

  case Anthropic
    extends LlmProvider(
      "Anthropic",
      "https://api.anthropic.com",
      "claude-sonnet-5",
      "claude-haiku-4-5",
      "claude-opus-5",
    )

  // No abler model is assumed to answer chat completions, so OpenAI's thorough
  // tier is its standard model unless one is configured.
  case OpenAi
    extends LlmProvider(
      "OpenAI",
      "https://api.openai.com",
      "gpt-5",
      "gpt-5-mini",
      "gpt-5",
    )

  case Gemini
    extends LlmProvider(
      "Gemini",
      "https://generativelanguage.googleapis.com",
      "gemini-2.5-flash",
      "gemini-2.5-flash-lite",
      "gemini-2.5-pro",
    )

  /** The model this provider uses for the given tier unless told otherwise. */
  def model(tier: ModelTier): String = tier match
    case ModelTier.Fast     => fastModel
    case ModelTier.Standard => defaultModel
    case ModelTier.Thorough => thoroughModel

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
  *
  * @param timeout
  *   How long to wait for a completion before giving up. Generous by default,
  *   because producing a long one can take a model several minutes.
  *
  * @param fastModel
  *   The model to prompt for [[ModelTier.Fast]] work, when not the provider's
  *   own choice for it.
  *
  * @param thoroughModel
  *   The model to prompt for [[ModelTier.Thorough]] work, when not the
  *   provider's own choice for it.
  */
final case class LlmConfig
  (
    provider: LlmProvider,
    apiKey: String,
    model: String,
    maxTokens: Int,
    baseUrl: Option[String] = None,
    timeout: FiniteDuration = LlmConfig.defaultTimeout,
    fastModel: Option[String] = None,
    thoroughModel: Option[String] = None,
  ):

  /**
    * What a request made with the given options is settled on: the model it
    * names, else the one configured for the tier it asks for, else [[model]].
    */
  private[iris] def settings(options: CompletionOptions): Settings = Settings(
    model = options
      .model
      .getOrElse(modelFor(options.tier.getOrElse(ModelTier.Standard))),
    maxTokens = options.maxTokens.getOrElse(maxTokens),
  )

  /**
    * The model prompted for the given tier: [[model]] for the standard tier,
    * and for the others the one configured, else the provider's own.
    */
  def modelFor(tier: ModelTier): String = tier match
    case ModelTier.Fast     => fastModel.getOrElse(provider.model(tier))
    case ModelTier.Standard => model
    case ModelTier.Thorough => thoroughModel.getOrElse(provider.model(tier))

  /** The API origin to send to: [[baseUrl]] when set, else the provider's. */
  def origin: String = baseUrl.getOrElse(provider.origin)

  /** Describes this configuration without its [[apiKey]], so it is safe to log. */
  override def toString: String =
    s"LlmConfig($provider, <redacted>, $model, $maxTokens, $baseUrl, $timeout, $fastModel, $thoroughModel)"

object LlmConfig:

  /** The environment variables that may hold provider API keys. */
  val apiKeyVariables: List[String] = LlmProvider
    .values
    .toList
    .flatMap(keyNames)

  /** The completion token limit used when none is configured. */
  val defaultMaxTokens: Int = 8192

  /** How long to wait for a completion when no timeout is configured. */
  val defaultTimeout: FiniteDuration = 5.minutes

  /** The optional configuration environment variables. */
  val settingVariables: List[String] = List(
    "LLM_PROVIDER",
    "LLM_MODEL",
    "LLM_MODEL_FAST",
    "LLM_MODEL_THOROUGH",
    "LLM_MAX_TOKENS",
    "LLM_BASE_URL",
    "LLM_TIMEOUT",
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
    *   - `LLM_MODEL_FAST` / `LLM_MODEL_THOROUGH`: override the provider's model
    *     for [[ModelTier.Fast]] and [[ModelTier.Thorough]] work.
    *   - `LLM_MAX_TOKENS`: the completion token limit (default
    *     `defaultMaxTokens`). When present but not a positive whole number, no
    *     configuration is produced.
    *   - `LLM_BASE_URL`: overrides the provider's API origin.
    *   - `LLM_TIMEOUT`: how long to wait for a completion, in seconds (default
    *     `defaultTimeout`). When present but not a positive whole number, no
    *     configuration is produced.
    *
    * @return
    *   A configuration, or `None` when no provider API key is set, or when a
    *   variable which is set cannot be used.
    */
  def fromEnv: Option[LlmConfig] =
    for
      provider  <- env("LLM_PROVIDER").fold(inferProvider)(LlmProvider.parse)
      apiKey    <- apiKey(provider)
      maxTokens <-
        env("LLM_MAX_TOKENS").fold(Some(defaultMaxTokens))(tokenLimit)
      timeout <- env("LLM_TIMEOUT").fold(Some(defaultTimeout))(seconds)
    yield LlmConfig(
      provider = provider,
      apiKey = apiKey,
      model = env("LLM_MODEL").getOrElse(provider.defaultModel),
      maxTokens = maxTokens,
      baseUrl = env("LLM_BASE_URL"),
      timeout = timeout,
      fastModel = env("LLM_MODEL_FAST"),
      thoroughModel = env("LLM_MODEL_THOROUGH"),
    )

  /**
    * Reads a timeout in seconds, which must be a positive whole number, on the
    * same terms as [[tokenLimit]]: a value which is not is no timeout at all.
    */
  private[iris] def seconds(value: String): Option[FiniteDuration] =
    tokenLimit(value).map(_.seconds)

  /**
    * Reads a completion token limit, which must be a positive whole number. A
    * value which is not is no limit at all, rather than a silent fall back to
    * [[defaultMaxTokens]] which would hide the mistake.
    */
  private[iris] def tokenLimit(value: String): Option[Int] = value
    .trim
    .toIntOption
    .filter(_ > 0)

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
