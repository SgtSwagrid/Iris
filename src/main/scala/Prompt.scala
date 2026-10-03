package com.alecdorrington.iris

/**
  * A single-turn prompt. For multi-turn conversations, use [[Chat]].
  *
  * @param user
  *   The user message.
  *
  * @param system
  *   The system message, if any.
  */
final case class Prompt
  (
    user: String,
    system: Option[String] = None,
  ):

  /** This prompt as a single-message chat. */
  def toChat: Chat = Chat(List(Message(Role.User, user)), system)
