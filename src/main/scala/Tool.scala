package com.alecdorrington.iris

import io.circe.Json

/**
  * A tool the model may ask to have run on its behalf. Offered per request
  * through [[CompletionOptions.tools]]; a model which wants one says so in
  * [[Completion.toolCalls]], and the answer travels back as a
  * [[Part.ToolResult]] in the next message.
  *
  * Iris never runs a tool itself. What a tool does, whether it is allowed to do
  * it, and how long it may take are the host's to decide.
  *
  * @param name
  *   The name the model calls this tool by, unique among those offered.
  *
  * @param description
  *   What the tool does, in words, which is what the model chooses by.
  *
  * @param parameters
  *   A [JSON Schema](https://json-schema.org) object describing the arguments
  *   the tool takes. Every provider here speaks this dialect.
  */
final case class Tool
  (
    name: String,
    description: String,
    parameters: Json,
  )
