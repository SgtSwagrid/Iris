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
(model override, token limit, temperature, top-p, stop sequences), and each
[`Completion`](src/main/scala/Completion.scala) carries the reply text along with a
normalised `StopReason` and token usage counts.

```scala
client.send(chat, CompletionOptions(temperature = Some(0.2), stopSequences = List("\n\n")))
```

### Errors

A provider's refusal fails the effect with an [`LlmError`](src/main/scala/LlmError.scala):
`Http` for an unsuccessful response, carrying its status and body, and `Malformed` for a response
that could not be understood. Response bodies may contain provider detail you would rather not show
to your own users, so consider logging them rather than passing them on.

## ⚙️ Configuration

`LlmConfig.fromEnv` (and so `LlmClient.fromEnv`) reads these environment variables:

| Variable            | Meaning                                     | Default                        |
|---------------------|---------------------------------------------|--------------------------------|
| `LLM_PROVIDER`      | `anthropic`, `openai` or `gemini`           | Inferred from which key exists |
| `ANTHROPIC_API_KEY` | API key for Anthropic                       | -                              |
| `OPENAI_API_KEY`    | API key for OpenAI                          | -                              |
| `GEMINI_API_KEY`    | API key for Gemini (or `GOOGLE_API_KEY`)    | -                              |
| `LLM_MODEL`         | Model name to use                           | Provider-specific default      |
| `LLM_MAX_TOKENS`    | Maximum number of tokens in each completion | `8192`                         |
| `LLM_BASE_URL`      | Overrides the provider's API origin         | The provider's own origin      |

With no key set, `fromEnv` yields `None`, and a set but unrecognised `LLM_PROVIDER` does too,
rather than falling back to whichever key exists.

## 🤝 Contributing

Iris is developed as part of a larger private project, of which this repository is an automatically synchronised
mirror (by [GitHub Graph](https://github.com/SgtSwagrid/github-graph)), so changes made here directly would be overwritten.
Issues are very welcome; for anything more, please open an issue first.

## 👁️ See also

- [Hecate](https://github.com/SgtSwagrid/Hecate), a sibling, for user accounts, sessions, groups and permissions.
- [Eunomia](https://github.com/SgtSwagrid/Eunomia), a sibling, for filtering, ordering and paging lists.
- This library was made using [Scala Library Template](https://github.com/SgtSwagrid/scala-library-template).
