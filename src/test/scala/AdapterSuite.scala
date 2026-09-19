package com.alecdorrington.iris

import io.circe.Json
import io.circe.parser.{decode, parse}
import munit.FunSuite

class AdapterSuite extends FunSuite:

  private val config = LlmConfig(
    LlmProvider.Anthropic,
    "key",
    "model-x",
    512,
  )

  private val chat = Chat()
    .withSystem("Be brief.")
    .user("Hello")
    .assistant("Hi!")
    .user("How are you?")

  private def json(body: String): Json = parse(body).toOption.get

  private def roles(json: Json, field: String): Option[List[String]] = json
    .hcursor
    .downField(field)
    .as[List[Json]]
    .toOption
    .map(_.flatMap(_.hcursor.get[String]("role").toOption))

  test("anthropic requests include history, system and tuning options"):
    val body = json(AnthropicClient.requestJson(
      config,
      chat,
      CompletionOptions(
        temperature = Some(0.5),
        stopSequences = List("END"),
      ),
    ))
    assertEquals(
      body.hcursor.get[String]("model").toOption,
      Some("model-x"),
    )
    assertEquals(
      body.hcursor.get[Int]("max_tokens").toOption,
      Some(512),
    )
    assertEquals(
      body.hcursor.get[Double]("temperature").toOption,
      Some(0.5),
    )
    assertEquals(
      body.hcursor.get[String]("system").toOption,
      Some("Be brief."),
    )
    assertEquals(
      body.hcursor.get[List[String]]("stop_sequences").toOption,
      Some(List("END")),
    )
    assertEquals(
      roles(body, "messages"),
      Some(List("user", "assistant", "user")),
    )

  test("unset options are omitted from request bodies"):
    val body =
      json(AnthropicClient.requestJson(config, chat, CompletionOptions()))
    assert(body.hcursor.downField("temperature").failed)
    assert(body.hcursor.downField("top_p").failed)
    assert(body.hcursor.downField("stop_sequences").failed)

  test("openai requests put the system message first"):
    val body = json(OpenAiClient.requestJson(config, chat, CompletionOptions()))
    assertEquals(
      roles(body, "messages"),
      Some(List("system", "user", "assistant", "user")),
    )

  test("gemini requests use the model role and nested parts"):
    val body = json(GeminiClient.requestJson(config, chat, CompletionOptions()))
    assertEquals(
      roles(body, "contents"),
      Some(List("user", "model", "user")),
    )
    assert(body.hcursor.downField("system_instruction").succeeded)
    assertEquals(
      body
        .hcursor
        .downField("generationConfig")
        .get[Int]("maxOutputTokens")
        .toOption,
      Some(512),
    )

  test("per-request options override the configured model"):
    val body = json(OpenAiClient.requestJson(
      config,
      chat,
      CompletionOptions(
        model = Some("model-y"),
        maxTokens = Some(64),
      ),
    ))
    assertEquals(
      body.hcursor.get[String]("model").toOption,
      Some("model-y"),
    )
    assertEquals(
      body.hcursor.get[Int]("max_completion_tokens").toOption,
      Some(64),
    )

  test("anthropic responses parse into completions"):
    val body = """{"content":[{"type":"text","text":"Hello!"}],
         "stop_reason":"end_turn",
         "usage":{"input_tokens":10,"output_tokens":5}}"""
    assertEquals(
      decode[AnthropicClient.Response](body).toOption.get.completion,
      Completion(
        "Hello!",
        StopReason.Completed,
        Some(Usage(10, 5)),
      ),
    )

  test("openai responses parse into completions"):
    val body =
      """{"choices":[{"message":{"content":"Hello!"},"finish_reason":"length"}],
         "usage":{"prompt_tokens":4,"completion_tokens":2}}"""
    assertEquals(
      decode[OpenAiClient.Response](body).toOption.get.completion,
      Completion(
        "Hello!",
        StopReason.MaxTokens,
        Some(Usage(4, 2)),
      ),
    )

  test("gemini responses parse into completions"):
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},
         "finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}"""
    assertEquals(
      decode[GeminiClient.Response](body).toOption.get.completion,
      Completion(
        "Hello!",
        StopReason.Completed,
        Some(Usage(7, 3)),
      ),
    )

  test("unrecognised stop reasons are preserved"):
    val body = """{"content":[],"stop_reason":"refusal","usage":null}"""
    assertEquals(
      decode[AnthropicClient.Response](body).toOption.get.completion.stopReason,
      StopReason.Other("refusal"),
    )

  test("configurations never reveal their api key"):
    val secret = config.copy(apiKey = "sk-do-not-print")
    assert(!secret.toString.contains("sk-do-not-print"))
