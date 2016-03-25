package scodec.interop.akka.stream.decode

import scodec.Err

final case class UnexpectedEndOfStream(context: List[String]) extends Err {
  def this() = this(Nil)
  def message = s"unexpected end of stream"
  def pushContext(ctx: String) = copy(context = ctx :: context)
}
