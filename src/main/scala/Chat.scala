package com.alecdorrington.iris

import io.circe.Json

/** The author of a [[Message]] in a [[Chat]]. */
enum Role:

  /** The person (or application) conversing with the model. */
  case User

  /** The model itself. */
  case Assistant

  /** The name a role is sent under, where a provider uses this one. */
  def wire: String = this match
    case User      => "user"
    case Assistant => "assistant"

/**
  * One part of a message. A message is a list of these, so that text and the
  * media it refers to may travel together in the order they are meant to be
  * read.
  *
  * A sealed trait rather than an `enum`, so that each case is a type of its
  * own: [[Chat.results]] and [[Completion.toolCalls]] speak of one particular
  * kind of part, which an enum's cases would widen away.
  */
sealed trait Part

object Part:

  /** Written text. */
  final case class Text(text: String) extends Part

  /**
    * A picture, a document, or anything else a model may be given to look at.
    *
    * @param mediaType
    *   The IANA media type of the data, e.g. `image/png`.
    *
    * @param data
    *   The content itself, Base64 encoded.
    */
  final case class Media(mediaType: String, data: String) extends Part

  /**
    * The model asking for a tool to be run. Part of the model's own message,
    * and sent back with the conversation so that the model can see what it
    * asked for.
    *
    * @param id
    *   What the provider calls this request, by which its result is matched to
    *   it. Gemini names no such thing, so its tool's name stands in.
    *
    * @param name
    *   The name of the [[Tool]] to run.
    *
    * @param arguments
    *   The arguments to run it with, as described by [[Tool.parameters]].
    */
  final case class ToolRequest(id: String, name: String, arguments: Json)
    extends Part

  /**
    * What running a tool produced, answering a [[ToolRequest]]. Part of the
    * user's next message, since it is the host which speaks here.
    *
    * @param id
    *   The [[ToolRequest.id]] this answers.
    *
    * @param name
    *   The name of the tool which was run, which Gemini matches on.
    *
    * @param content
    *   What the tool produced, for the model to read.
    */
  final case class ToolResult(id: String, name: String, content: String)
    extends Part

  /**
    * The end of a prefix worth caching: everything before this point, the
    * system message included, is sent alike by many requests, so the provider
    * may keep what it made of it rather than read it afresh each time. It says
    * nothing to the model itself.
    *
    * Anthropic caches only what it is told to, and is told by this: the block
    * just before it is marked, at most four times in one chat. OpenAI and
    * Gemini cache every long prefix of their own accord, and are sent nothing.
    * A prefix too short for the provider to cache is simply not cached.
    *
    * Requests sent at once cannot read what none of them has yet written, so
    * [[LlmClient.warm]] writes the prefix before a batch of them is sent.
    */
  case object CacheBreakpoint extends Part

/**
  * A single message in a [[Chat]].
  *
  * @param role
  *   The author of this message.
  *
  * @param content
  *   What this message is made of, in the order it is to be read.
  */
final case class Message(role: Role, content: List[Part]):

  /** The written text of this message, with any media left out. */
  def text: String = content
    .collect:
      case Part.Text(text) => text
    .mkString

  /**
    * Whether this message is nothing but text, with no breakpoint in it either,
    * which some providers must be sent apart from the text.
    */
  def isText: Boolean = content.forall(_.isInstanceOf[Part.Text])

  /** This message with any cache breakpoints left out. */
  def uncached: Message =
    copy(content = content.filterNot(_ == Part.CacheBreakpoint))

  /** The results this message carries, which some providers send apart. */
  def toolResults: List[Part.ToolResult] = content.collect:
    case result: Part.ToolResult => result

object Message:

  /** A message of text alone. */
  def apply(role: Role, text: String): Message =
    Message(role, List(Part.Text(text)))

/**
  * The full history of a conversation with a model. Clients are stateless:
  * nothing is remembered between calls, so the entire history is supplied with
  * every request. To continue a conversation, append the model's reply and the
  * next user message, then send the chat again:
  *
  * {{{
  * for
  *   first  <- client.send(chat)
  *   next    = chat.assistant(first.text).user("Tell me more.")
  *   second <- client.send(next)
  * yield second
  * }}}
  *
  * A chat is sent to be continued, so it should hold at least one message and
  * end with the user's, as it does when built up through [[user]] and
  * [[assistant]] in turn. A chat which ends with the assistant's own message
  * asks the model to continue its own reply, which the newer Anthropic models
  * refuse; an empty one asks nothing of anybody, which every provider refuses.
  * Neither is rejected by the type, so both are reported as
  * [[LlmError.Unsendable]] rather than spending a request to be told.
  *
  * @param messages
  *   Every message exchanged so far, oldest first.
  *
  * @param system
  *   An optional system message, establishing general model behaviour.
  */
final case class Chat
  (
    messages: List[Message] = List.empty,
    system: Option[String] = None,
  ):

  /** This chat with the given message appended. */
  def add(message: Message): Chat = copy(messages = messages :+ message)

  /** This chat with a user message appended. */
  def user(content: String): Chat = add(Message(Role.User, content))

  /** This chat with a user message of the given parts appended. */
  def user(content: Part*): Chat = add(Message(Role.User, content.toList))

  /** This chat with an assistant message of the given parts appended. */
  def assistant(content: Part*): Chat =
    add(Message(Role.Assistant, content.toList))

  /** This chat with the model's reply, tool requests and all, appended. */
  def reply(completion: Completion): Chat = add(Message(
    Role.Assistant,
    Part.Text(completion.text) +: completion.toolCalls,
  ))

  /** This chat with the results of the model's tool requests appended. */
  def results(results: Part.ToolResult*): Chat =
    add(Message(Role.User, results.toList))

  /** This chat with an assistant message appended. */
  def assistant(content: String): Chat = add(Message(Role.Assistant, content))

  /** This chat with the given system message. */
  def withSystem(instructions: String): Chat = copy(system = Some(instructions))

  /**
    * The part of this chat worth caching: everything up to its last
    * [[Part.CacheBreakpoint]], which is kept, so that the prefix is sent just
    * as the whole chat sends it. Nothing but the system message when no message
    * holds a breakpoint, which is no chat to send.
    */
  def cacheable: Chat =
    val last = messages.lastIndexWhere(_.content.contains(Part.CacheBreakpoint))
    copy(messages =
      if last < 0 then List.empty
      else
        val message = messages(last)
        messages.take(last) :+ message.copy(content =
          message
            .content
            .take(message.content.lastIndexOf(Part.CacheBreakpoint) + 1),
        ),
    )
