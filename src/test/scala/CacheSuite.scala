package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.alecdorrington.iris.Fixtures.{config, json}
import io.circe.Json
import io.circe.parser.decode
import munit.FunSuite
import sttp.client4.StringBody
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode

/**
  * Tests of caching a chat's prefix: where each provider is told of a
  * breakpoint, what it reports of its cache, and warming one.
  */
class CacheSuite extends FunSuite:

  private val options = CompletionOptions()

  /** A long document, shared by every request, then a question about it. */
  private val chat = Chat()
    .withSystem("Be brief.")
    .user(
      Part.Text("A long document."),
      Part.CacheBreakpoint,
      Part.Text("A question about it."),
    )

  /** The content of the given message of a request body. */
  private def content(body: Json, message: Int): Json = body
    .hcursor
    .downField("messages")
    .downN(message)
    .downField("content")
    .focus
    .get

  private val ephemeral = Json.obj("type" -> Json.fromString("ephemeral"))

  private def text(text: String): Json = Json.obj(
    "type" -> Json.fromString("text"),
    "text" -> Json.fromString(text),
  )

  private def marked(text: String): Json = this
    .text(text)
    .deepMerge(Json.obj("cache_control" -> ephemeral))

  test("anthropic marks the block just before a breakpoint"):
    val body = json(AnthropicClient.requestJson(config, chat, options))
    assertEquals(
      content(body, 0),
      Json.arr(
        marked("A long document."),
        text("A question about it."),
      ),
    )
    assertEquals(
      body.hcursor.get[String]("system").toOption,
      Some("Be brief."),
    )

  test("a breakpoint opening the first message marks the system message"):
    val body = json(AnthropicClient.requestJson(
      config,
      Chat()
        .withSystem("Be brief.")
        .user(Part.CacheBreakpoint, Part.Text("Hi")),
      options,
    ))
    assertEquals(
      body.hcursor.downField("system").focus,
      Some(Json.arr(marked("Be brief."))),
    )
    assertEquals(content(body, 0), Json.fromString("Hi"))

  test("a breakpoint opening a later message marks the one before it"):
    val body = json(AnthropicClient.requestJson(
      config,
      Chat()
        .user("Hello")
        .assistant("Hi!")
        .user(
          Part.CacheBreakpoint,
          Part.Text("How are you?"),
        ),
      options,
    ))
    assertEquals(
      content(body, 1),
      Json.arr(marked("Hi!")),
    )
    assertEquals(
      content(body, 2),
      Json.fromString("How are you?"),
    )

  test("a breakpoint with nothing at all before it marks nothing"):
    val body = json(AnthropicClient.requestJson(
      config,
      Chat().user(Part.CacheBreakpoint, Part.Text("Hi")),
      options,
    ))
    assert(body.hcursor.downField("system").failed)
    assertEquals(content(body, 0), Json.fromString("Hi"))

  test("anthropic refuses more breakpoints than it keeps"):
    val many = Chat().user(
      List
        .fill(AnthropicClient.maxBreakpoints + 1)(
          List(Part.Text("x"), Part.CacheBreakpoint),
        )
        .flatten*,
    )
    assert(AnthropicClient.breakable(many).isLeft)
    assert(AnthropicClient.breakable(chat).isRight)

  test("anthropic counts the blocks breakpoints mark, not the breakpoints"):
    val breakpoints =
      List.fill(AnthropicClient.maxBreakpoints + 1)(Part.CacheBreakpoint)
    val together = Chat().user(Part.Text("x") +: breakpoints*)
    val leading  = Chat().user(breakpoints :+ Part.Text("x")*)
    assert(AnthropicClient.breakable(together).isRight)
    assert(AnthropicClient.breakable(leading).isRight)
    assert(AnthropicClient.breakable(leading.withSystem("Be brief.")).isRight)

  test("openai and gemini are sent no breakpoints"):
    val openAi = json(OpenAiClient.requestJson(config, chat, options))
    assertEquals(
      content(openAi, 1),
      Json.fromString("A long document.A question about it."),
    )
    val gemini = json(GeminiClient.requestJson(config, chat, options))
    assertEquals(
      gemini.hcursor.downField("contents").downN(0).downField("parts").focus,
      Some(Json.arr(
        Json.obj("text" -> Json.fromString("A long document.")),
        Json.obj("text" -> Json.fromString("A question about it.")),
      )),
    )

  test("a chat's cacheable part runs to its last breakpoint"):
    val longer = chat
      .assistant("An answer.")
      .user(
        Part.Text("Another."),
        Part.CacheBreakpoint,
        Part.Text("More."),
      )
    assertEquals(
      longer.cacheable,
      Chat(
        chat.messages :+ Message(Role.Assistant, "An answer.") :+ Message(
          Role.User,
          List(
            Part.Text("Another."),
            Part.CacheBreakpoint,
          ),
        ),
        chat.system,
      ),
    )
    assertEquals(
      chat.cacheable.messages,
      List(Message(
        Role.User,
        List(
          Part.Text("A long document."),
          Part.CacheBreakpoint,
        ),
      )),
    )
    assertEquals(
      Chat().user("Hi").cacheable.messages,
      Nil,
    )

  test("a prefix not ending with the user saying something is followed by it"):
    val opening = Chat()
      .user("Hello")
      .assistant("Hi!")
      .user(
        Part.CacheBreakpoint,
        Part.Text("How are you?"),
      )
    val closing = Chat()
      .user("Hello")
      .assistant(Part.Text("Hi!"), Part.CacheBreakpoint)
      .user("How are you?")
    List(opening, closing).foreach: whole =>
      val prefix = whole.cacheable
      assertEquals(
        prefix.messages.map(_.role),
        List(Role.User, Role.Assistant, Role.User),
      )
      assert(JsonHttp.answerable(LlmProvider.Anthropic, prefix).isRight)
      assert(!prefix.messages.map(_.text).mkString.contains("How are you?"))
      assertEquals(
        content(
          json(AnthropicClient.requestJson(config, prefix, options)),
          1,
        ),
        content(
          json(AnthropicClient.requestJson(config, whole, options)),
          1,
        ),
      )

  test("a breakpoint opening a message after the user's closes theirs"):
    val whole = Chat()
      .user("Hello")
      .assistant(Part.CacheBreakpoint, Part.Text("Hi!"))
      .user("How are you?")
    assertEquals(
      whole.cacheable.messages,
      List(Message(
        Role.User,
        List(
          Part.Text("Hello"),
          Part.CacheBreakpoint,
        ),
      )),
    )

  test("anthropic reports cache reads and writes as input"):
    val body = """{"content":[{"type":"text","text":"Hi"}],
         "stop_reason":"end_turn",
         "usage":{"input_tokens":10,"output_tokens":5,
                  "cache_creation_input_tokens":20,
                  "cache_read_input_tokens":30}}"""
    assertEquals(
      decode[AnthropicClient.Response](body)
        .toOption
        .get
        .completion
        .map(_.usage),
      Right(Some(Usage(60, 5, 30))),
    )

  test("openai reports the prompt tokens read from its cache"):
    val body =
      """{"choices":[{"message":{"content":"Hi"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":40,"completion_tokens":2,
                  "prompt_tokens_details":{"cached_tokens":32}}}"""
    assertEquals(
      decode[OpenAiClient.Response](body).toOption.get.completion.map(_.usage),
      Right(Some(Usage(40, 2, 32))),
    )

  test("gemini reports the prompt tokens read from its cache"):
    val body = """{"candidates":[{"content":{"parts":[{"text":"Hi"}]},
         "finishReason":"STOP"}],
         "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":2,
                          "cachedContentTokenCount":32}}"""
    assertEquals(
      decode[GeminiClient.Response](body).toOption.get.completion.map(_.usage),
      Right(Some(Usage(40, 2, 32))),
    )

  /**
    * A client which answers only a request whose body satisfies the given test,
    * and refuses any other, so that a warm which succeeds was sent as the test
    * expects.
    */
  private def expecting
    (config: LlmConfig, reply: String)
    (test: Json => Boolean)
    : LlmClient[IO] = LlmClient[IO](
    config,
    BackendStub(CatsMonadAsyncError[IO])
      .whenRequestMatches(request =>
        request.body match
          case StringBody(body, _, _) => test(json(body))
          case _                      => false,
      )
      .thenRespond(ResponseStub.adjust(reply, StatusCode.Ok))
      .whenAnyRequest
      .thenRespond(ResponseStub.adjust("unexpected", StatusCode.BadRequest)),
  )

  /** Whether a request body carries only the chat's cacheable prefix. */
  private def prefixOnly(body: Json): Boolean =
    val sent = body.noSpaces
    sent.contains("A long document.") && !sent.contains("A question about it.")

  test("anthropic warms a prefix without asking for a reply"):
    val warming = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens",
         "usage":{"input_tokens":0,"output_tokens":0,
                  "cache_creation_input_tokens":900}}""",
    )(body =>
      body.hcursor.get[Int]("max_tokens").toOption.contains(0) &&
      prefixOnly(body),
    )
    assertEquals(
      warming.warm(chat).attempt.unsafeRunSync().map(_.usage),
      Right(Some(Usage(900, 0))),
    )

  test("other providers warm a prefix asking for as little as they allow"):
    val warming = expecting(
      config.copy(provider = LlmProvider.OpenAi),
      """{"choices":[{"message":{"content":""},"finish_reason":"length"}],
         "usage":{"prompt_tokens":900,"completion_tokens":1}}""",
    )(body =>
      body.hcursor.get[Int]("max_completion_tokens").toOption.contains(1) &&
      prefixOnly(body),
    )
    assert(warming.warm(chat).attempt.unsafeRunSync().isRight)

  /** A chat caching its system message alone, as a breakpoint opening it does. */
  private val briefed = Chat()
    .withSystem("A long document.")
    .user(
      Part.CacheBreakpoint,
      Part.Text("A question about it."),
    )

  /**
    * Whether a request body carries only the system message of [[briefed]], and
    * messages each saying something, as every provider requires.
    */
  private def briefOnly
    (
      body: Json,
      messages: String,
      parts: String,
    )
    : Boolean =
    val sent = body.noSpaces
    sent.contains("A long document.") &&
    !sent.contains("A question about it.") &&
    body
      .hcursor
      .downField(messages)
      .as[List[Json]]
      .exists(all =>
        all.nonEmpty && all.forall(
          _.hcursor
            .downField(parts)
            .focus
            .exists(said =>
              said.asString.exists(_.nonEmpty) ||
              said.asArray.exists(_.nonEmpty),
            ),
        ),
      )

  test("a breakpoint opening the first message warms the system message"):
    val anthropic = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(body =>
      briefOnly(body, "messages", "content") &&
      body
        .hcursor
        .downField("system")
        .focus
        .contains(Json.arr(marked("A long document."))),
    )
    val openAi = expecting(
      config.copy(provider = LlmProvider.OpenAi),
      """{"choices":[{"message":{"content":""},"finish_reason":"length"}]}""",
    )(body =>
      briefOnly(body, "messages", "content") &&
      body.hcursor.downField("messages").downN(1).succeeded,
    )
    val gemini = expecting(
      config.copy(provider = LlmProvider.Gemini),
      """{"candidates":[{"content":{"parts":[{"text":""}]},
         "finishReason":"MAX_TOKENS"}]}""",
    )(briefOnly(_, "contents", "parts"))
    List(anthropic, openAi, gemini).foreach: client =>
      assert(client.warm(briefed).attempt.unsafeRunSync().isRight)

  test("a chat with nothing marked has nothing to warm"):
    val warming = expecting(
      config,
      """{"content":[],"stop_reason":"max_tokens"}""",
    )(_ => true)
    assert(warming.warm(Chat().user("Hi")).attempt.unsafeRunSync().isLeft)
