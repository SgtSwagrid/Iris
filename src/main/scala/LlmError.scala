package com.alecdorrington.iris

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
    * @param detail
    *   The response body, which usually describes the failure.
    */
  case Http
    (
      providerName: String,
      code: StatusCode,
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
