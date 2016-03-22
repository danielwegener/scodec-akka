package scodec.interop.akka.stream.decode


import scodec.bits.BitVector
import scodec.Err

sealed trait Step[+A] {
  def map[B](f: A => B): Step[B]
}

final case class Continue[+A](sdec:StreamDecoder[A]) extends Step[A] {
  def map[B](f: A => B): Step[B] = Continue(sdec.map(f))
}
case class Wait(bitCount:Long, context:List[String]) extends Step[Nothing] {
  def map[B](f: Nothing => B): Step[B] = this
}
case object Complete extends Step[Nothing] {
  def map[B](f: Nothing => B): Step[B] = this
}
case class Fail(err:Err) extends Step[Nothing] {
  def map[B](f: Nothing => B): Step[B] = this
}

case class StreamDecodeResult[+A](remainder:BitVector, decoded:Option[A], streamResult:Step[A]) {

  /** Maps the supplied function over the successful value, if present. */
  def map[B](f: A => B): StreamDecodeResult[B] = copy(decoded = decoded.map(f), streamResult = streamResult.map(f))

}

