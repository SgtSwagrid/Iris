package com.alecdorrington.iris

/**
  * A single-turn prompt to a large language model. For multi-turn
  * conversations, use [[Chat]] instead.
  *
  * @param user
  *   The user message, containing the input for the task at hand.
  *
  * @param system
  *   An optional system message, establishing general model behaviour.
  */
final case class Prompt
  (
    user: String,
    system: Option[String] = None,
  ):

  /** This prompt as a single-message chat. */
  def toChat: Chat = Chat(List(Message(Role.User, user)), system)
