package com.alecdorrington.iris

import fs2.{text, Stream}
import io.circe.Json
import io.circe.parser.parse

/**
  * Reading a [Server-Sent
  * Events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
  * body, which is how all three providers deliver a reply as it is written.
  */
private[iris] object Sse:

  /** The marker with which a provider says a stream is over. */
  private val done = "[DONE]"

  /**
    * The payload of one line, where the line carries one. Every other line — an
    * event name, a comment, the blank line between events — says nothing this
    * library needs, since the payload names its own kind.
    */
  def data(line: String): Option[String] = Option
    .when(line.startsWith("data:"))(line.drop("data:".length).trim)
    .filter(_.nonEmpty)
    .filterNot(_ == done)

  /** The events of a Server-Sent Events body, as the JSON they carry. */
  def events[F[_]](body: Stream[F, Byte]): Stream[F, Json] = body
    .through(text.utf8.decode)
    .through(text.lines)
    .map(data)
    .unNone
    .map(parse)
    .collect:
      case Right(json) => json

  /**
    * The reply which the given events make up, where each says what it says
    * alone, as the given function reads it: into a list, because a provider may
    * end a reply in the same breath as finishing it.
    */
  def each[F[_]]
    (read: Json => List[Delta])
    (events: Stream[F, Json])
    : Stream[F, Delta] = events.map(read).flatMap(Stream.emits)
