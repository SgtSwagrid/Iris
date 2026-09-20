package com.alecdorrington.iris

import io.circe.Json
import io.circe.parser.{decode, parse}
import munit.FunSuite
import scala.concurrent.duration.DurationInt

class AdapterSuite extends FunSuite:

  private val config = LlmConfig(
    LlmProvider.Anthropic,
    "key",
    "model-x",
    512,
  )

  /** As [[config]], for the provider whose endpoints carry their model. */
  private val gemini = config.copy(provider = LlmProvider.Gemini)

  private val options = CompletionOptions()

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

  test("anthropic omits sampling options for models that reject them"):
    val body = json(AnthropicClient.requestJson(
      config.copy(model = "claude-sonnet-5"),
      chat,
      CompletionOptions(temperature = Some(0.5), topP = Some(0.9)),
    ))
    assert(body.hcursor.downField("temperature").failed)
    assert(body.hcursor.downField("top_p").failed)

  test("anthropic keeps sampling options for models that accept them"):
    val body = json(AnthropicClient.requestJson(
      config.copy(model = "claude-haiku-4-5"),
      chat,
      CompletionOptions(temperature = Some(0.5), topP = Some(0.9)),
    ))
    assertEquals(
      body.hcursor.get[Double]("temperature").toOption,
      Some(0.5),
    )
    assertEquals(
      body.hcursor.get[Double]("top_p").toOption,
      Some(0.9),
    )

  private val picture = Part.Media("image/png", "aGVsbG8=")

  private val looking = Chat().user(Part.Text("What is this?"), picture)

  test("a message of text alone is still sent as plain text"):
    val body  = json(AnthropicClient.requestJson(config, chat, options))
    val first = body.hcursor.downField("messages").downN(0)
    assertEquals(
      first.get[String]("content").toOption,
      Some("Hello"),
    )

  test("anthropic sends a picture beside the text which asks about it"):
    val body  = json(AnthropicClient.requestJson(config, looking, options))
    val parts = body.hcursor.downField("messages").downN(0).downField("content")
    assertEquals(
      parts.downN(0).get[String]("type").toOption,
      Some("text"),
    )
    assertEquals(
      parts.downN(1).get[String]("type").toOption,
      Some("image"),
    )
    assertEquals(
      parts.downN(1).downField("source").get[String]("media_type").toOption,
      Some("image/png"),
    )

  test("anthropic sends anything which is not a picture as a document"):
    val paper = Chat().user(Part.Media("application/pdf", "JVBERi0="))
    val body  = json(AnthropicClient.requestJson(config, paper, options))
    assertEquals(
      body
        .hcursor
        .downField("messages")
        .downN(0)
        .downField("content")
        .downN(0)
        .get[String]("type")
        .toOption,
      Some("document"),
    )

  test("openai sends a picture as a data url"):
    val body  = json(OpenAiClient.requestJson(config, looking, options))
    val parts = body.hcursor.downField("messages").downN(0).downField("content")
    assertEquals(
      parts.downN(1).get[String]("type").toOption,
      Some("image_url"),
    )
    assertEquals(
      parts.downN(1).downField("image_url").get[String]("url").toOption,
      Some("data:image/png;base64,aGVsbG8="),
    )

  test("openai says it cannot carry a document in a chat"):
    val paper = Chat().user(Part.Media("application/pdf", "JVBERi0="))
    assertEquals(
      OpenAiClient.pictorial(paper),
      Left(LlmError.Unsupported(
        "OpenAI",
        "sending application/pdf in a chat",
      )),
    )
    assert(OpenAiClient.pictorial(looking).isRight)

  test("gemini sends a picture inline beside its text"):
    val body  = json(GeminiClient.requestJson(config, looking, options))
    val parts = body.hcursor.downField("contents").downN(0).downField("parts")
    assertEquals(
      parts.downN(0).get[String]("text").toOption,
      Some("What is this?"),
    )
    assertEquals(
      parts.downN(1).downField("inline_data").get[String]("mime_type").toOption,
      Some("image/png"),
    )

  test("counting asks for no completion, so needs no limit"):
    val body =
      json(AnthropicClient.countJson(config, chat, CompletionOptions()))
    assertEquals(
      body.hcursor.get[String]("model").toOption,
      Some("model-x"),
    )
    assert(body.hcursor.downField("max_tokens").failed)
    assertEquals(
      roles(body, "messages"),
      Some(List("user", "assistant", "user")),
    )

  test("gemini counts its system message among its contents"):
    val body = json(GeminiClient.countJson(chat))
    assertEquals(
      roles(body, "contents"),
      Some(List("user", "user", "model", "user")),
    )

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
      Right(Completion(
        "Hello!",
        StopReason.Completed,
        Some(Usage(10, 5)),
      )),
    )

  test("openai responses parse into completions"):
    val body =
      """{"choices":[{"message":{"content":"Hello!"},"finish_reason":"length"}],
         "usage":{"prompt_tokens":4,"completion_tokens":2}}"""
    assertEquals(
      decode[OpenAiClient.Response](body).toOption.get.completion,
      Right(Completion(
        "Hello!",
        StopReason.MaxTokens,
        Some(Usage(4, 2)),
      )),
    )

  test("gemini responses parse into completions"):
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"Hello!"}],"role":"model"},
         "finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":3}}"""
    assertEquals(
      decode[GeminiClient.Response](body).toOption.get.completion,
      Right(Completion(
        "Hello!",
        StopReason.Completed,
        Some(Usage(7, 3)),
      )),
    )

  test("unrecognised stop reasons are preserved"):
    val body = """{"content":[],"stop_reason":"refusal","usage":null}"""
    assertEquals(
      decode[AnthropicClient.Response](body)
        .toOption
        .get
        .completion
        .map(_.stopReason),
      Right(StopReason.Other("refusal")),
    )

  test("a reply with no choices is a malformed openai response"):
    val body = """{"choices":[],"usage":null}"""
    assertEquals(
      decode[OpenAiClient.Response](body).toOption.get.completion,
      Left(LlmError.Malformed("OpenAI", "no choices")),
    )

  test("a blocked gemini prompt is malformed, and says why"):
    val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
    assertEquals(
      decode[GeminiClient.Response](body).toOption.get.completion,
      Left(LlmError.Malformed(
        "Gemini",
        "no candidates, blocked as SAFETY",
      )),
    )

  test("a gemini reply with no candidates is malformed"):
    val body = """{"candidates":[]}"""
    assertEquals(
      decode[GeminiClient.Response](body).toOption.get.completion,
      Left(LlmError.Malformed("Gemini", "no candidates")),
    )

  test("a provider's request to wait is carried, where it gives one"):
    assertEquals(
      JsonHttp.retryAfter(Some("30")),
      Some(30.seconds),
    )
    assertEquals(
      JsonHttp.retryAfter(Some(" 0 ")),
      Some(0.seconds),
    )

  test("a request to wait which is not seconds is left unread"):
    assertEquals(JsonHttp.retryAfter(None), None)
    assertEquals(
      JsonHttp.retryAfter(Some("Wed, 21 Oct 2026 07:28:00 GMT")),
      None,
    )
    assertEquals(JsonHttp.retryAfter(Some("-1")), None)

  test("a chat with nothing in it is not sent"):
    assertEquals(
      JsonHttp.answerable(LlmProvider.Gemini, Chat()),
      Left(LlmError.Unsendable("Gemini", "it has no messages")),
    )
    assert(JsonHttp.answerable(LlmProvider.Gemini, chat).isRight)

  test("a model which refuses a prefill is not asked to continue one"):
    val prefilled = Chat().user("Hello").assistant("Once upon a")
    assert(AnthropicClient.continuable(prefilled, "claude-sonnet-5").isLeft)
    assert(AnthropicClient.continuable(prefilled, "claude-opus-4-6").isLeft)

  test("a model which accepts a prefill still may be given one"):
    val prefilled = Chat().user("Hello").assistant("Once upon a")
    assert(AnthropicClient.continuable(prefilled, "claude-haiku-4-5").isRight)
    assert(AnthropicClient.continuable(chat, "claude-sonnet-5").isRight)

  test("a stop reason the provider withheld is not one it gave"):
    val body = """{"content":[{"type":"text","text":"Hi"}]}"""
    assertEquals(
      decode[AnthropicClient.Response](body)
        .toOption
        .get
        .completion
        .map(_.stopReason),
      Right(StopReason.Unknown),
    )

  test("partial token counts are no usage, not a broken response"):
    val body = """{"content":[],"stop_reason":"end_turn",
         "usage":{"input_tokens":10}}"""
    assertEquals(
      decode[AnthropicClient.Response](body)
        .toOption
        .get
        .completion
        .map(_.usage),
      Right(None),
    )

  test("a response which reports no usage still parses"):
    val body =
      """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}]}"""
    assertEquals(
      decode[OpenAiClient.Response](body).toOption.get.completion,
      Right(Completion("Hi", StopReason.Completed, None)),
    )

  test("only blocks of text contribute to the reply"):
    val body = """{"content":[{"type":"thinking","text":"hmm"},
         {"type":"text","text":"Hello!"}],"stop_reason":"end_turn"}"""
    assertEquals(
      decode[AnthropicClient.Response](body)
        .toOption
        .get
        .completion
        .map(_.text),
      Right("Hello!"),
    )

  test("gemini addresses the generate endpoint of the model it is given"):
    assertEquals(
      GeminiClient.endpoint(gemini, "gemini-2.5-flash").map(_.toString),
      Right(
        "https://generativelanguage.googleapis.com" +
          "/v1beta/models/gemini-2.5-flash:generateContent",
      ),
    )

  test("a model name cannot escape the path segment it names"):
    val endpoint = GeminiClient.endpoint(gemini, "../v1/elsewhere")
    assert(endpoint.exists(uri => !uri.toString.contains("/v1/elsewhere")))

  test("a base url which is not a url is a configuration error"):
    val endpoint = GeminiClient.endpoint(
      gemini.copy(baseUrl = Some("https://proxy.test/%")),
      "model-x",
    )
    assert(endpoint.left.exists(_.isInstanceOf[LlmError.Misconfigured]))

  test("a base url is the origin beneath which endpoints are addressed"):
    assertEquals(
      GeminiClient
        .endpoint(
          gemini.copy(baseUrl = Some("https://proxy.test")),
          "model-x",
        )
        .map(_.toString),
      Right("https://proxy.test/v1beta/models/model-x:generateContent"),
    )
