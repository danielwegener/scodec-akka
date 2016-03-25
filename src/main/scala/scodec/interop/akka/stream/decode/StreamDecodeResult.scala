package scodec.interop.akka.stream.decode


import scodec.bits.BitVector
import scodec.Err

sealed trait Step[+A] {
  /** map over an decoded value */
  def map[B](f: A => B): Step[B]
  /** flatMap over an decoded value */
  def flatMap[B](f: A => Option[B]): Step[B]
  /** maps over an err */
  def mapErr(f: Err=>Err):Step[A] = this

}

final case class Continue[+A](sdec:StreamDecoder[A]) extends Step[A] {
  def map[B](f: A => B): Step[B] = Continue(sdec.map(f))
  def flatMap[B](f: A => Option[B]): Step[B] = Continue(sdec.flatMapDecoded(f))
  override def mapErr(f:Err=>Err) = this
}

case class RequestMoreBits(bitCount:Long, soft:Boolean, context:List[String]) extends Step[Nothing] {
  def map[B](f: Nothing => B): Step[B] = this
  def flatMap[B](f: Nothing => Option[B]): Step[B] = this
}
case object Complete extends Step[Nothing] {
  def map[B](f: Nothing => B): Step[B] = this
  def flatMap[B](f: Nothing => Option[B]): Step[B] = this
}
case class Fail(err:Err) extends Step[Nothing] {
  def map[B](f: Nothing => B): Step[B] = this
  def flatMap[B](f: Nothing => Option[B]): Step[B] = this
  def pushCtx(ctx:String):Fail = copy(err.pushContext(ctx))
  override def mapErr(f:Err=>Err) = Fail(f(err))
}

case class StreamDecodeResult[+A](remainder:BitVector, decoded:Option[A], nextStep:Step[A]) {

  /** Maps the supplied function over emitted values and all values the following step may produce */
  def map[B](f: A => B): StreamDecodeResult[B] = StreamDecodeResult[B](remainder, decoded.map(f), nextStep.map(f))
  def flatMap[B](f: A => Option[B]): StreamDecodeResult[B] = StreamDecodeResult[B](remainder, decoded.flatMap(f), nextStep.flatMap(f))
  def mapNextStep[B>:A](f:A=>B): StreamDecodeResult[B] = copy(nextStep = nextStep)
  def mapErr(f:Err=>Err):StreamDecodeResult[A] = copy(nextStep = nextStep.mapErr(f))

}

