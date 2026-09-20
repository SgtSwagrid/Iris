package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite

/** Tests of reading a reply as it is written. */
class StreamSuite extends FunSuite:

  private val config = LlmConfig(
    LlmProvider.Anthropic,
    "key",
    "model-x",
    512,
  )

  private def json(body: String): Json = parse(body).toOption.get

  /** The deltas read from a Server-Sent Events body, as one provider reads it. */
  private def deltas(body: String)(read: Json => List[Delta]): List[Delta] =
    Stream
      .emits(body.getBytes("UTF-8"))
      .covary[IO]
      .through(Sse.events)
      .map(read)
      .flatMap(Stream.emits)
      .compile
      .toList
      .unsafeRunSync()

  test("a data line carries its payload, and other lines carry none"):
    assertEquals(
      Sse.data("data: {\"a\":1}"),
      Some("{\"a\":1}"),
    )
    assertEquals(
      Sse.data("data:{\"a\":1}"),
      Some("{\"a\":1}"),
    )
    assertEquals(Sse.data("event: message_stop"), None)
    assertEquals(Sse.data(": a comment"), None)
    assertEquals(Sse.data(""), None)

  test("the marker which ends a stream is not an event"):
    assertEquals(Sse.data("data: [DONE]"), None)

  test("anthropic streams its text and how it ended"):
    val body = """event: content_block_delta
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}

event: content_block_delta
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"lo"}}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

event: message_stop
data: {"type":"message_stop"}
"""
    assertEquals(
      deltas(body)(AnthropicClient.deltas),
      List(
        Delta.Text("Hel"),
        Delta.Text("lo"),
        Delta.End(StopReason.Completed, None),
      ),
    )

  test("openai streams its text, how it ended, and what it cost"):
    val body = """data: {"choices":[{"delta":{"content":"Hel"}}]}

data: {"choices":[{"delta":{"content":"lo"}}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}

data: [DONE]
"""
    assertEquals(
      deltas(body)(OpenAiClient.deltas),
      List(
        Delta.Text("Hel"),
        Delta.Text("lo"),
        Delta.End(StopReason.Completed, Some(Usage(3, 2))),
      ),
    )

  test("gemini may speak and finish in the same breath"):
    val body = """data: {"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}

data: {"candidates":[{"content":{"parts":[{"text":"lo"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}
"""
    assertEquals(
      deltas(body)(GeminiClient.deltas),
      List(
        Delta.Text("Hel"),
        Delta.Text("lo"),
        Delta.End(StopReason.Completed, Some(Usage(7, 3))),
      ),
    )

  test("an event which says nothing new is not reported"):
    val body = """data: {"type":"message_start","message":{"id":"msg_1"}}

data: {"type":"content_block_start","index":0}

data: {"type":"ping"}
"""
    assertEquals(
      deltas(body)(AnthropicClient.deltas),
      List.empty,
    )

  test("a truncated reply says so where it ended"):
    val body =
      """data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}
"""
    assertEquals(
      deltas(body)(AnthropicClient.deltas),
      List(Delta.End(StopReason.MaxTokens, None)),
    )

  test("a streaming request asks to be streamed"):
    val body = json(AnthropicClient.requestJson(
      config,
      Chat().user("Hi"),
      CompletionOptions(),
      true,
    ))
    assertEquals(
      body.hcursor.get[Boolean]("stream").toOption,
      Some(true),
    )

  test("an ordinary request does not ask to be streamed"):
    val body = json(AnthropicClient.requestJson(
      config,
      Chat().user("Hi"),
      CompletionOptions(),
    ))
    assert(body.hcursor.downField("stream").failed)

  test("openai asks for the usage a stream would otherwise withhold"):
    val body = json(OpenAiClient.requestJson(
      config,
      Chat().user("Hi"),
      CompletionOptions(),
      true,
    ))
    assertEquals(
      body
        .hcursor
        .downField("stream_options")
        .get[Boolean]("include_usage")
        .toOption,
      Some(true),
    )

  test("gemini streams from an endpoint of its own"):
    val gemini = config.copy(provider = LlmProvider.Gemini)
    assertEquals(
      GeminiClient.streaming(gemini, "gemini-2.5-flash").map(_.toString),
      Right(
        "https://generativelanguage.googleapis.com" +
          "/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
      ),
    )
