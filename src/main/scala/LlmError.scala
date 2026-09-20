package com.alecdorrington.iris

import scala.concurrent.duration.FiniteDuration
import sttp.model.StatusCode

/**
  * An error produced while communicating with an LLM provider. Structured so
  * that callers can tell retryable failures (e.g. rate limits) apart from
  * configuration mistakes and broken responses.
  */
enum LlmError(message: String) extends Exception(message):

  /**
    * The provider returned an unsuccessful HTTP status.
    *
    * @param providerName
    *   The name of the provider, for display.
    *
    * @param code
    *   The HTTP status code of the response.
    *
    * @param retryAfter
    *   How long the provider asked us to wait before trying again, where it
    *   said. Set on a rate limit, and on some outages.
    *
    * @param detail
    *   The response body, which usually describes the failure.
    */
  case Http
    (
      providerName: String,
      code: StatusCode,
      retryAfter: Option[FiniteDuration],
      detail: String,
    ) extends LlmError(s"$providerName request failed ($code): $detail")

  /**
    * The provider returned a response whose body could not be decoded.
    *
    * @param providerName
    *   The name of the provider, for display.
    *
    * @param detail
    *   A description of the decoding failure.
    */
  case Malformed(providerName: String, detail: String)
    extends LlmError(s"Unexpected $providerName response: $detail")

  /**
    * The client is configured in a way it cannot be used, such as with a base
    * URL which is not a URL. Raised in place of the request it prevented.
    *
    * @param providerName
    *   The name of the provider, for display.
    *
    * @param detail
    *   A description of what cannot be used.
    */
  case Misconfigured(providerName: String, detail: String)
    extends LlmError(s"Cannot address $providerName: $detail")

  /**
    * The chat is one this provider will not answer, so it was not asked to.
    * Raised in place of the request it prevented.
    *
    * @param providerName
    *   The name of the provider, for display.
    *
    * @param detail
    *   A description of what the provider would not have answered.
    */
  case Unsendable(providerName: String, detail: String)
    extends LlmError(s"$providerName will not answer this chat: $detail")

  /**
    * The provider's API offers no way to do what was asked, so nothing was
    * asked of it. Not a failure of one request, but of every such request.
    *
    * @param providerName
    *   The name of the provider, for display.
    *
    * @param detail
    *   A description of what the provider cannot do.
    */
  case Unsupported(providerName: String, detail: String)
    extends LlmError(s"$providerName cannot do this: $detail")
