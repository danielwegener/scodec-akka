package scodec.interop.akka.stream.decode

import scodec.Err
import scodec.bits.BitVector

final case class BitsAfterCodecCompletion(bits:BitVector, context: List[String] = Nil) extends Err {
  def message = s"received bits"
  def pushContext(ctx: String) = copy(context = ctx :: context)
}
