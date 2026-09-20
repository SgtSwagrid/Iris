package com.alecdorrington.iris

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import scala.concurrent.duration.DurationInt
import sttp.client4.impl.cats.CatsMonadAsyncError
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.{Header, StatusCode}

/**
  * Tests of a whole [[LlmClient]], over a stubbed backend, covering what the
  * adapters do with a response rather than what they put in a request.
  */
class ClientSuite extends FunSuite:

  private val config = LlmConfig(
    LlmProvider.Anthropic,
    "key",
    "model-x",
    512,
  )

  private val prompt = Prompt("Hello")

  /** A client answering every request with the given response. */
  private def client
    (
      body: String,
      status: StatusCode = StatusCode.Ok,
      headers: Seq[Header] = Seq.empty,
      config: LlmConfig = config,
    )
    : LlmClient[IO] = LlmClient[IO](
    config,
    BackendStub(CatsMonadAsyncError[IO])
      .whenAnyRequest
      .thenRespond(ResponseStub.adjust(body, status, headers)),
  )

  /** What the given client makes of its one stubbed response. */
  private def result(client: LlmClient[IO]): Either[Throwable, Completion] =
    client.complete(prompt).attempt.unsafeRunSync()

  test("a successful response becomes a completion"):
    val body = """{"content":[{"type":"text","text":"Hi"}],
         "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":1}}"""
    assertEquals(
      result(client(body)),
      Right(Completion(
        "Hi",
        StopReason.Completed,
        Some(Usage(3, 1)),
      )),
    )

  test("an unsuccessful response is an http error carrying its status"):
    val failure = result(client(
      "rate limited",
      StatusCode.TooManyRequests,
    ))
    assertEquals(
      failure,
      Left(LlmError.Http(
        "Anthropic",
        StatusCode.TooManyRequests,
        None,
        "rate limited",
      )),
    )

  test("a provider's request to wait reaches the error it belongs to"):
    val failure = result(client(
      "slow down",
      StatusCode.TooManyRequests,
      Seq(Header("Retry-After", "30")),
    ))
    assertEquals(
      failure
        .left
        .toOption
        .collect:
          case LlmError.Http(_, _, retryAfter, _) => retryAfter
      ,
      Some(Some(30.seconds)),
    )

  test("a response which is not json at all is malformed"):
    assert(
      result(client("<html>down for maintenance</html>"))
        .left
        .exists:
          case LlmError.Malformed("Anthropic", _) => true
          case _                                  => false,
    )

  test("a base url which is not a url fails the effect, never the call"):
    val broken = config.copy(baseUrl = Some("https://proxy.test/%"))
    assert(
      result(client("{}", config = broken))
        .left
        .exists:
          case LlmError.Misconfigured("Anthropic", _) => true
          case _                                      => false,
    )

  test("a chat with nothing in it never reaches the provider"):
    val empty = client("{}").send(Chat()).attempt.unsafeRunSync()
    assert(
      empty
        .left
        .exists:
          case LlmError.Unsendable("Anthropic", _) => true
          case _                                   => false,
    )

  test("anthropic counts a chat before it is sent"):
    val counted = client("""{"input_tokens":42}""")
      .count(prompt)
      .attempt
      .unsafeRunSync()
    assertEquals(counted, Right(42))

  test("gemini counts a chat before it is sent"):
    val gemini  = config.copy(provider = LlmProvider.Gemini)
    val counted = client(
      """{"totalTokens":17}""",
      config = gemini,
    ).count(prompt).attempt.unsafeRunSync()
    assertEquals(counted, Right(17))

  test("openai says it cannot count rather than guessing"):
    val openAi  = config.copy(provider = LlmProvider.OpenAi)
    val counted = client("{}", config = openAi)
      .count(prompt)
      .attempt
      .unsafeRunSync()
    assertEquals(
      counted,
      Left(LlmError.Unsupported("OpenAI", "counting tokens")),
    )

  test("each provider is given its own adapter"):
    val gemini = config.copy(provider = LlmProvider.Gemini)
    val body   = """{"candidates":[{"content":{"parts":[{"text":"Hei"}]},
         "finishReason":"STOP"}]}"""
    assertEquals(
      result(client(body, config = gemini)).map(_.text),
      Right("Hei"),
    )
