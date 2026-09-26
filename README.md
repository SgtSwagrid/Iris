<div align="center">

  <h1>🌈 Iris</h1>
  <p>A provider-agnostic <a href="https://www.scala-lang.org/">Scala</a> client for large language models.</p>

  <span>
    <a href="https://github.com/SgtSwagrid/Iris/actions/workflows/build-integrity.yml"><img src="https://github.com/SgtSwagrid/Iris/actions/workflows/build-integrity.yml/badge.svg" alt="Build status" /></a>
    <a href="https://search.maven.org/artifact/com.alecdorrington/iris_3"><img src="https://img.shields.io/maven-central/v/com.alecdorrington/iris_3.svg" alt="Maven Central" /></a>
    <a href="https://alecdorrington.com/Iris"><img src="https://img.shields.io/badge/docs-latest-blue.svg" alt="Documentation" /></a>
  </span>

</div>

> [!WARNING]
> Iris is in beta. It is young, it has one user, and anything may change between minor versions.

One small interface for sending prompts and conversations to a large language model,
with adapters for [Anthropic](https://docs.anthropic.com/en/api/messages),
[OpenAI](https://platform.openai.com/docs/api-reference/chat) and
[Google Gemini](https://ai.google.dev/api/generate-content), so that switching provider is a matter of configuration.
It is built on [Cats Effect](https://typelevel.org/cats-effect/), [sttp](https://sttp.softwaremill.com/) and [Circe](https://circe.github.io/circe/).

Named for [Iris](https://en.wikipedia.org/wiki/Iris_(mythology)), messenger of the gods,
who carried their words to mortals along the rainbow.

## ⬇️ Installation

Add the following to your `build.sbt`:

```scala
libraryDependencies += "com.alecdorrington" %% "iris" % "0.1.0"
```

Compiled with Scala `3.8.4`, with no intention to explicitly support older versions. JVM only.

## 🚀 Usage

The public interface is [`LlmClient`](src/main/scala/LlmClient.scala).
Build one for an explicit [`LlmConfig`](src/main/scala/LlmConfig.scala) with `LlmClient.resource`,
over an sttp backend of your own with `LlmClient(config, backend)`,
or from the environment (see [Configuration](#%EF%B8%8F-configuration)) with `LlmClient.fromEnv`.

### Single-turn prompts

```scala
import com.alecdorrington.iris.{LlmClient, Prompt}

LlmClient.fromEnv[IO].use {
  case Some(client) => client.complete(Prompt("Hello!"))
  case None         => // No provider configured.
}
```

### Conversations

Conversations are stateless: nothing is remembered between calls, and the full history
travels with every request as a [`Chat`](src/main/scala/Chat.scala).
To continue a conversation, append the model's reply and the next user message, then send the chat again:

```scala
import com.alecdorrington.iris.Chat

val chat = Chat().withSystem("You are terse.").user("Name a colour.")
for
  first  <- client.send(chat)
  second <- client.send(chat.assistant(first.text).user("And another."))
yield second.text
```

### Tuning and metadata

Each request accepts [`CompletionOptions`](src/main/scala/CompletionOptions.scala)
(model override, model tier, token limit, temperature, top-p, stop sequences), and each
[`Completion`](src/main/scala/Completion.scala) carries the reply text along with a
normalised `StopReason` and token usage counts.

```scala
client.send(chat, CompletionOptions(maxTokens = Some(1024), stopSequences = List("\n\n")))
```

Rather than name a model, a request may ask for a `ModelTier`: `Fast` for simple, mechanical work,
`Standard` for the configured model, or `Thorough` for work needing judgement. Each tier prompts the
model configured for it (`LLM_MODEL_FAST`, `LLM_MODEL`, `LLM_MODEL_THOROUGH`), else the provider's own
choice: Anthropic's Haiku, Sonnet and Opus; OpenAI's `gpt-5-mini`, then `gpt-5` for both of the
others; and Gemini's Flash-Lite, Flash and Pro.

```scala
client.send(chat, CompletionOptions(tier = Some(ModelTier.Fast)))
```

> [!NOTE]
> Anthropic's newer models, the default `claude-sonnet-5` among them, no longer accept sampling
> parameters and reject a request which carries them. Iris omits `temperature` and top-p for those
> models rather than let the request fail; both still apply to every other provider, and to
> Anthropic's older models.

### Pictures and documents

A message is a list of [`Part`](src/main/scala/Chat.scala)s, so text may travel beside
the media it refers to. A message of text alone is still sent as plain text, so nothing
changes for a conversation which carries none.

```scala
import com.alecdorrington.iris.Part

chat.user(Part.Text("What is in this picture?"), Part.Media("image/png", base64))
```

Anthropic and Gemini take both pictures and documents. OpenAI's chat completions take
pictures only, and a document there fails with `LlmError.Unsupported` rather than being
dropped or sent as something it is not.

### Streaming

Where a reader is waiting, [`LlmStream`](src/main/scala/LlmStream.scala) delivers the reply
as it is written. It is a capability apart from `LlmClient`, because it needs a backend which
can stream.

```scala
import com.alecdorrington.iris.{Delta, LlmStream}

LlmStream.fromEnv[IO].use {
  case Some(llm) => llm.stream(Prompt("Tell me a story.")).evalMap {
    case Delta.Text(text)          => IO.print(text)
    case Delta.End(reason, usage)  => IO.println(s"
($reason)")
  }.compile.drain
  case None => IO.unit
}
```

Nothing is sent until the stream is run, and a failure reaches it as an `LlmError` as it
would anywhere else. `Delta.End` carries the token counts where the provider reports them
as it ends; Anthropic counts the input at the start instead, so `send` remains the way to
have both halves together.

### Tools

Offer the model tools it may ask to have run, and it will say so in `Completion.toolCalls`.
Iris never runs a tool itself; what one does, and whether it is allowed to, is yours to decide.

```scala
import com.alecdorrington.iris.{Part, Tool}

val weather = Tool("weather", "Looks up the weather.", schema)

for
  asked  <- client.send(chat, CompletionOptions(tools = List(weather)))
  result  = Part.ToolResult(asked.toolCalls.head.id, "weather", lookUp(asked.toolCalls.head))
  answer <- client.send(chat.reply(asked).results(result))
yield answer.text
```

A reply which asked for a tool has `StopReason.ToolUse`, on every provider — Gemini reports
no such reason of its own, so the asking is what says so.

### Counting tokens

`count` asks the provider what a chat would cost to send, so a conversation can be
checked against a budget or a context window before a completion is spent finding out.

```scala
client.count(Prompt("How long is a piece of string?"))
```

OpenAI offers no such endpoint, and fails with `LlmError.Unsupported` rather than
guessing with a tokeniser of its own.

### Caching

When many requests begin alike, as when one document is asked several questions, put what
they share first and end it with a `Part.CacheBreakpoint`, so that the provider can keep what it
made of the prefix rather than read it afresh every time. `Usage.cachedTokens` says how much of a
prompt was read from the cache.

```scala
val asking = (question: String) =>
  Chat().withSystem("Answer from the document.")
    .user(Part.Text(document), Part.CacheBreakpoint, Part.Text(question))

for
  _       <- client.warm(asking(""))
  answers <- questions.parTraverse(question => client.send(asking(question)))
yield answers
```

Requests sent at once cannot read what none of them has written yet, so `warm` writes the prefix
first: everything up to the last breakpoint, asking for as little reply as the provider allows.

Anthropic caches only what it is told to, and a breakpoint marks the block before it, at most four
times in one chat. OpenAI and Gemini cache long prefixes of their own accord and are sent nothing.
Each provider keeps a cache per model, and caches nothing shorter than its own minimum.

### Errors

A provider's refusal fails the effect with an [`LlmError`](src/main/scala/LlmError.scala):
`Http` for an unsuccessful response, carrying its status and body, and `Malformed` for a response
that could not be understood. Response bodies may contain provider detail you would rather not show
to your own users, so consider logging them rather than passing them on.

## ⚙️ Configuration

`LlmConfig.fromEnv` (and so `LlmClient.fromEnv`) reads these environment variables:

| Variable             | Meaning                                     | Default                        |
|----------------------|---------------------------------------------|--------------------------------|
| `LLM_PROVIDER`       | `anthropic`, `openai` or `gemini`           | Inferred from which key exists |
| `ANTHROPIC_API_KEY`  | API key for Anthropic                       | -                              |
| `OPENAI_API_KEY`     | API key for OpenAI                          | -                              |
| `GEMINI_API_KEY`     | API key for Gemini (or `GOOGLE_API_KEY`)    | -                              |
| `LLM_MODEL`          | Model name to use                           | Provider-specific default      |
| `LLM_MODEL_FAST`     | Model for `ModelTier.Fast` requests         | Provider-specific default      |
| `LLM_MODEL_THOROUGH` | Model for `ModelTier.Thorough` requests     | Provider-specific default      |
| `LLM_MAX_TOKENS`     | Maximum number of tokens in each completion | `8192`                         |
| `LLM_BASE_URL`       | Overrides the provider's API origin         | The provider's own origin      |
| `LLM_TIMEOUT`        | Seconds to wait for a completion            | `300`                          |

With no key set, `fromEnv` yields `None`. So does a variable which is set but cannot be used:
an unrecognised `LLM_PROVIDER`, rather than falling back to whichever key exists, and an
`LLM_MAX_TOKENS` which is not a positive whole number, rather than quietly reverting to the
default and hiding the mistake.

## 🤝 Contributing

Iris is developed as part of a larger private project, of which this repository is an automatically synchronised
mirror (by [GitHub Graph](https://github.com/SgtSwagrid/github-graph)), so changes made here directly would be overwritten.
Issues are very welcome; for anything more, please open an issue first.

## 👁️ See also

- [Hecate](https://github.com/SgtSwagrid/Hecate), a sibling, for user accounts, sessions, groups and permissions.
- [Eunomia](https://github.com/SgtSwagrid/Eunomia), a sibling, for filtering, ordering and paging lists.
- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
