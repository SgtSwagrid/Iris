package com.alecdorrington.iris

import io.circe.Json
import io.circe.parser.{decode, parse}
import munit.FunSuite

/** Tests of offering a tool, being asked for one, and answering. */
class ToolSuite extends FunSuite:

  private val config = LlmConfig(
    LlmProvider.Anthropic,
    "key",
    "model-x",
    512,
  )

  private val weather = Tool(
    "weather",
    "Looks up the weather somewhere.",
    Json.obj(
      "type"       -> Json.fromString("object"),
      "properties" ->
        Json.obj("city" -> Json.obj("type" -> Json.fromString("string"))),
    ),
  )

  private val offering = CompletionOptions(tools = List(weather))

  private val asked = Part.ToolRequest(
    "call_1",
    "weather",
    Json.obj("city" -> Json.fromString("Zug")),
  )

  private val answered = Part.ToolResult("call_1", "weather", "Snowing.")

  /** A chat in which a tool was asked for and answered. */
  private val exchange = Chat()
    .user("What is the weather in Zug?")
    .assistant(asked)
    .results(answered)

  private def json(body: String): Json = parse(body).toOption.get

  test("anthropic offers a tool by its schema"):
    val body =
      json(AnthropicClient.requestJson(config, Chat().user("Hi"), offering))
    val tool = body.hcursor.downField("tools").downN(0)
    assertEquals(
      tool.get[String]("name").toOption,
      Some("weather"),
    )
    assertEquals(
      tool.downField("input_schema").get[String]("type").toOption,
      Some("object"),
    )

  test("openai offers a tool as a function"):
    val body =
      json(OpenAiClient.requestJson(config, Chat().user("Hi"), offering))
    val tool = body.hcursor.downField("tools").downN(0)
    assertEquals(
      tool.get[String]("type").toOption,
      Some("function"),
    )
    assertEquals(
      tool.downField("function").get[String]("name").toOption,
      Some("weather"),
    )

  test("gemini declares every tool together"):
    val body =
      json(GeminiClient.requestJson(config, Chat().user("Hi"), offering))
    assertEquals(
      body
        .hcursor
        .downField("tools")
        .downN(0)
        .downField("functionDeclarations")
        .downN(0)
        .get[String]("name")
        .toOption,
      Some("weather"),
    )

  test("no tools are offered when none are given"):
    val body = json(AnthropicClient.requestJson(
      config,
      Chat().user("Hi"),
      CompletionOptions(),
    ))
    assert(body.hcursor.downField("tools").failed)

  test("anthropic sends a request and its result as blocks"):
    val body     = json(AnthropicClient.requestJson(config, exchange, offering))
    val messages = body.hcursor.downField("messages")
    val request  = messages.downN(1).downField("content").downN(0)
    val result   = messages.downN(2).downField("content").downN(0)
    assertEquals(
      request.get[String]("type").toOption,
      Some("tool_use"),
    )
    assertEquals(
      request.get[String]("id").toOption,
      Some("call_1"),
    )
    assertEquals(
      result.get[String]("type").toOption,
      Some("tool_result"),
    )
    assertEquals(
      result.get[String]("tool_use_id").toOption,
      Some("call_1"),
    )

  test("openai sends a result as a message of its own"):
    val body     = json(OpenAiClient.requestJson(config, exchange, offering))
    val messages = body.hcursor.downField("messages")
    assertEquals(
      messages.downN(1).get[String]("role").toOption,
      Some("assistant"),
    )
    assertEquals(
      messages
        .downN(1)
        .downField("tool_calls")
        .downN(0)
        .downField("function")
        .get[String]("arguments")
        .toOption,
      Some("""{"city":"Zug"}"""),
    )
    assertEquals(
      messages.downN(2).get[String]("role").toOption,
      Some("tool"),
    )
    assertEquals(
      messages.downN(2).get[String]("tool_call_id").toOption,
      Some("call_1"),
    )

  test("gemini sends a request and its result as parts"):
    val body     = json(GeminiClient.requestJson(config, exchange, offering))
    val contents = body.hcursor.downField("contents")
    assertEquals(
      contents
        .downN(1)
        .downField("parts")
        .downN(0)
        .downField("functionCall")
        .get[String]("name")
        .toOption,
      Some("weather"),
    )
    assertEquals(
      contents
        .downN(2)
        .downField("parts")
        .downN(0)
        .downField("functionResponse")
        .get[String]("name")
        .toOption,
      Some("weather"),
    )

  test("anthropic reports the tool it asked for"):
    val body       = """{"content":[{"type":"text","text":"Let me look."},
         {"type":"tool_use","id":"call_1","name":"weather",
          "input":{"city":"Zug"}}],"stop_reason":"tool_use"}"""
    val completion = decode[AnthropicClient.Response](body)
      .toOption
      .get
      .completion
    assertEquals(
      completion.map(_.toolCalls),
      Right(List(asked)),
    )
    assertEquals(
      completion.map(_.stopReason),
      Right(StopReason.ToolUse),
    )
    assertEquals(
      completion.map(_.text),
      Right("Let me look."),
    )

  test("openai reports the tool it asked for, arguments parsed"):
    val body       = """{"choices":[{"message":{"content":null,"tool_calls":[
         {"id":"call_1","type":"function",
          "function":{"name":"weather","arguments":"{\"city\":\"Zug\"}"}}]},
         "finish_reason":"tool_calls"}]}"""
    val completion = decode[OpenAiClient.Response](body).toOption.get.completion
    assertEquals(
      completion.map(_.toolCalls),
      Right(List(asked)),
    )
    assertEquals(
      completion.map(_.stopReason),
      Right(StopReason.ToolUse),
    )

  test("gemini reports the tool it asked for, named for want of an id"):
    val body       = """{"candidates":[{"content":{"parts":[
         {"functionCall":{"name":"weather","args":{"city":"Zug"}}}]},
         "finishReason":"STOP"}]}"""
    val completion = decode[GeminiClient.Response](body).toOption.get.completion
    assertEquals(
      completion.map(_.toolCalls),
      Right(List(asked.copy(id = "weather"))),
    )
    assertEquals(
      completion.map(_.stopReason),
      Right(StopReason.ToolUse),
    )

  test("a model's reply can be appended and answered"):
    val completion = Completion(
      "Let me look.",
      StopReason.ToolUse,
      None,
      List(asked),
    )
    val next = Chat().user("Weather?").reply(completion).results(answered)
    assertEquals(next.messages.size, 3)
    assertEquals(next.messages(1).role, Role.Assistant)
    assertEquals(next.messages(1).content.last, asked)
    assertEquals(
      next.messages(2).content,
      List(answered),
    )
