package scodec.interop.akka.stream.decode


import scodec.Attempt.{Failure, Successful}
import scodec.{DecodeResult, Err, Decoder}
import scodec.Err.InsufficientBits
import scodec.bits.BitVector

trait StreamDecoder[+A] { self =>

  /**
    * Attempts to decode a value of type `A` from the specified bit vector.
    *
    * @param bits bits to decode
    * @return a stream decode result.
    */
  def decode(bits:BitVector):StreamDecodeResult[A]

  /**
    * @group combinators
    */
  def ~[B] (thenDecoder: StreamDecoder[B])(implicit ev: A<:<B) = new StreamDecoder[B] {
      def decode(bits: BitVector): StreamDecodeResult[B] =
        self.as[B].decode(bits) match {
          case dsr@StreamDecodeResult(_,_,Complete) => dsr.asInstanceOf[StreamDecodeResult[B]].copy(streamResult = Continue(thenDecoder))
          case o => o.asInstanceOf[StreamDecodeResult[B]]
        }
      override def toString:String = s"${StreamDecoder.this} ~ $thenDecoder"
    }

  /**
    * Converts this stream decoder to a new stream decoder that fails decoding if there are remaining bits after the given stream codec completes.
    */
  def complete: StreamDecoder[A] = new StreamDecoder[A] {
    override def toString:String = s"$self.complete"
    override def decode(bits: BitVector): StreamDecodeResult[A] = self.decode(bits) match {
      case StreamDecodeResult(remainder,decoded,Complete) if !remainder.isEmpty =>
        StreamDecodeResult(remainder,decoded,Fail(BitsAfterCodecCompletion(remainder)))
      case StreamDecodeResult(remainder,decoded,Complete) =>
        StreamDecodeResult(remainder,decoded, Continue(StreamDecoder.expectSilence))
      case o => o
    }
  }

  def as[B](implicit ev: A<:<B):StreamDecoder[B] = self.asInstanceOf[StreamDecoder[B]]

  /**
    * Converts this stream decoder to a `StreamDecoder[B]` using the supplied `A => B`.
    * @group combinators
    */
  def map[B](f:A=>B):StreamDecoder[B] = new StreamDecoder[B] {
    def decode(bits: BitVector): StreamDecodeResult[B] = StreamDecoder.this.decode(bits).map(f)
  }

  /**
    * Converts this decoder to a `StreamDecoder[B]` using the supplied `A => StreamDecoder[B]`.
    * It will first decode the value of this stream codec and use that value to construct a stream codec for subsequent bits.
    * @group combinators
    */
  def flatMap[B](f: A => StreamDecoder[B]): StreamDecoder[B] = new StreamDecoder[B] {
    def decode(bits: BitVector):StreamDecodeResult[B] = ???
  }

}

object StreamDecoder {

  /**
    * Gets a stream decoder that decodes the given stream decoder successfully but discards all of it's emitted values.
    * This stream decoder fails if the underlying stream decoder fails.
    * @group combinators
    */
  /*def expect[T](s:StreamDecoder[T]): StreamDecoder[Nothing] = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector): StreamDecodeResult[Nothing] = s.decode(bits) match {
      case StreamDecodeResult(remainder,decoded,streamResult) => StreamDecodeResult(remainder, None, ???)
    }
    override def toString = s"expect($s)"
  }*/

  /**
    * Gets a stream decoder that instantly fails with a [[BitsAfterCodecCompletion]] whenever it is fed with a BitVector.
    */
  def expectSilence = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector): StreamDecodeResult[Nothing] = StreamDecodeResult(bits, None, Fail(BitsAfterCodecCompletion(bits)))
    override def toString = s"expectSilence"
  }

  /**
    * Gets a stream decoder that emits a given element and completes without consuming bits.
    * This stream decoder can never fail.
    */
  def emit[A](a:A):StreamDecoder[A] =
    new StreamDecoder[A] {
      override def decode(bits: BitVector): StreamDecodeResult[A] =
        StreamDecodeResult(bits, Some(a), Complete)
      override def toString = s"emit($a)"
    }

  /**
    * Get a stream decoder that emits nothing and completes immediately without consuming bits.
    * This stream decoder can never fail.
    */
  def halt = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector): StreamDecodeResult[Nothing] = StreamDecodeResult(bits, None, Complete)
    override def toString = s"halt"
  }


  /**
    * Gets a stream decoder that decodes a single A and completes.
    * This stream decoder fails if the underlying decoder fails.
    * It may reject and wait until sufficient bits are available.
    */
  def once[A](dec: Decoder[A]) = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = {
      dec.decode(bits) match {
        case s@Successful(DecodeResult(value, remainder)) => StreamDecodeResult(remainder, Some(value), Complete)
        case f@Failure(InsufficientBits(needed,_,ctx)) => StreamDecodeResult(bits, None, Wait(needed, ctx))
        case f@Failure(e) => StreamDecodeResult(bits, None, Fail(e))
      }
    }
    override def toString = s"once(dec)"
  }

  /**
    * Gets a stream codec that consumes a given number of bits from the buffer.
    * It may reject and wait until sufficient bits are available.
    */
  def ignore(bitsToSkip:Long):StreamDecoder[Nothing] = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector) =
      if (bits.length >= bitsToSkip) {
        StreamDecodeResult(bits.drop(bitsToSkip), None, Complete)
      } else {
        StreamDecodeResult(bits, None, Wait(bitsToSkip - bits.length, List(this.toString)))
      }
    override def toString = s"ignore($bitsToSkip)"
  }

  /**
    * Gets a stream codec that immediately fails with the given err without consuming bits.
    */
  def fail(err:Err) = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector) = StreamDecodeResult(bits, None, Fail(err))
    override def toString = s"fail($err)"
  }

  /**
    * Gets a stream coded that repeats to decode elements from the given decoder.
    * This stream decoder fails if the underlying decoder fails.
    * It may reject and wait until sufficient bits are available.
    */
  def repeat[A](dec:Decoder[A]):StreamDecoder[A] = repeat(once(dec))

  def repeat[A](s:StreamDecoder[A]):StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = {
      s.decode(bits) match {
        case sdr@StreamDecodeResult(_,_,Complete) => sdr.copy(streamResult = Continue(repeat(s)))
        case otherwise => otherwise
      }
    }
    override def toString = s"repeat($s)"
  }

}
