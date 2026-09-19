# CLAUDE.md

This file provides guidance to [Claude Code](https://claude.com/product/claude-code) when working with code in this repository.
It is not intended for human eyes.

### Maintenance

You (robot or human) have standing permission to update this file without asking.
Add important patterns, gotchas, or context that would help future sessions.
Keep it concise and actionable.

## Project overview

This is Iris, a Scala 3 library giving one provider-agnostic interface to the APIs of large language models, with
adapters for Anthropic, OpenAI and Google Gemini, built on Cats Effect, sttp and Circe. It is in beta.

It is a single JVM module in `com.alecdorrington.iris`. `LlmClient` is the public interface (`send` a `Chat`,
or `complete` a `Prompt`), with one adapter per `LlmProvider` (`AnthropicClient`, `OpenAiClient`, `GeminiClient`,
sharing `JsonHttp`). `LlmConfig` holds provider, key, model, token limit and an optional base URL, and
`LlmConfig.fromEnv` reads them from `LLM_*` and the providers' `*_API_KEY` variables.

- Clients are stateless: no session identity reaches the provider, and a conversation's whole history travels with
  each `Chat`. Keep it that way.
- Every adapter must normalise what it returns: the reply text, a `StopReason` (unknown labels kept as `Other`) and
  token usage in `Completion`. An unsuccessful response is `LlmError.Http` (status and body) and an unreadable one
  `LlmError.Malformed`, so hosts can tell rate limits from broken responses; transport failures are sttp's own.
- A set but unrecognised `LLM_PROVIDER` means unconfigured (`None`), never a fall back to key inference.
- The library does no throttling, retrying or logging of its own; those are the host's to wrap around a client.
- `LlmConfig.toString` redacts the API key, so hosts may log a configuration. Keep any new secret out of `toString`
  and out of `LlmError` messages likewise.

See [README.md](README.md) for usage.

### Where this code lives

This repository is a mirror. The library is developed inside a larger private project, beneath `iris/`, and every file
here is copied from there by [GitHub Graph](https://github.com/SgtSwagrid/github-graph) whenever that project's `main`
changes, overwriting whatever is here. So make changes there, never here. The shared configuration (workflows, Scalafmt, IDE settings, `project/plugins-*.sbt`) comes from further upstream still, in
[Scala Library Config](https://github.com/SgtSwagrid/scala-library-config), which syncs into the private project's `iris/` first.
`build.sbt`, `release.sbt`, `project/Dependencies.scala`, `README.md` and this file belong to the library.

### Build

- The root project `iris` is the library itself, published as `iris`. Its id is the library's name because the private
  project includes this build by reference (`ProjectRef(file("iris"), "iris")`), alongside projects of its own.
- The library must never depend on anything in the project that includes it.
- Versions come from git tags (`sbt-ci-release`); publishing a GitHub release publishes to Maven Central.

## Instructions

### Compilation and Diagnostics

- When the user asks for help with a compilation or type error, start by running `sbt compile` to see the error for yourself.
  If there are many errors, making it unclear which one the user is referring to, ask them to clarify, and then focus only on that issue.
- IntelliJ MCP integration is active. When a request seems to implicitly refer to something the user is looking at, always check
  `mcp__ide__getDiagnostics` first to see which file(s) are open and get associated diagnostics (errors, warnings, and info hints with line numbers).

#### Testing

- After making code changes, always run `sbt compile` to verify that issues are fixed and no new ones are introduced.
- Repeatedly retry upon failure until the build succeeds. If you are unsure how to fix an issue, ask for help or refer to existing code for examples.
- Before trying to fix an error, make sure you first understand it fully.
- You should never report that a feature is complete without testing it first.

### Code Style

- You must read the [Code Style Guidelines](docs/STYLE_GUIDE.md).

### Pull Requests

When asked to publish the code changes, your task is to open one or more pull requests (PRs) to merge the changes into `main` on GitHub:

- Use `git` to check what has changed as compared to the `main` branch on `origin`.
- If the changes are thematically linked, they can be published as a single PR.
- Otherwise, you'll need to divide the changes into multiple PRs using your own judgement.
- Each PR should have a singular focus, shouldn't break anything, and should be able to be merged independently.
- Ensure that all code is staged, committed and pushed. Ensure no new files are left uncommitted, and no debug code is left in the codebase.
- When creating a PR, ensure that the title and description are clear, informative, and comprehensive.
- All feature/bugfix/etc branch names should be formatted as "feature_<short description>" or "fix_<short description>" or similar.
- All PR titles should be formatted as "[<scope>] <Short summary>", e.g. "[renderer] Fixed colour inversion bug."
- You have GitHub MCP integration that can be used to do the above.
