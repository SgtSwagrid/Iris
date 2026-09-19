package com.alecdorrington.iris

/** The author of a [[Message]] in a [[Chat]]. */
enum Role:

  /** The person (or application) conversing with the model. */
  case User

  /** The model itself. */
  case Assistant

  /** The name a role is sent under, by every provider that shares it. */
  def wire: String = this match
    case User      => "user"
    case Assistant => "assistant"

/**
  * A single message in a [[Chat]].
  *
  * @param role
  *   The author of this message.
  *
  * @param content
  *   The text of this message.
  */
final case class Message(role: Role, content: String)

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

  /** This chat with an assistant message appended. */
  def assistant(content: String): Chat = add(Message(Role.Assistant, content))

  /** This chat with the given system message. */
  def withSystem(instructions: String): Chat = copy(system = Some(instructions))
