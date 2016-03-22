package scodec.interop.akka.stream.decode

import scodec.Err

final case class InsufficientBitsAtCompletion(needed: Long, have: Long, context: List[String]) extends Err {
  def this(needed: Long, have: Long) = this(needed, have, Nil)
  def message = s"cannot acquire $needed bits from a vector that contains $have bits and upstream terminated"
  def pushContext(ctx: String) = copy(context = ctx :: context)
}
