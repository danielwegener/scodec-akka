package scodec.interop.akka.stream.decode


import scodec.Attempt.{Failure, Successful}
import scodec.{DecodeResult, Err, Decoder}
import scodec.Err.InsufficientBits
import scodec.bits.BitVector
import scodec._

trait StreamDecoder[+A] { self =>


  /**
    * Attempts to decode a value of type `A` from the specified bit vector.
    *
    * @param bits bits to decode
    * @return a stream decode result.
    */
  def decode(bits:BitVector):StreamDecodeResult[A]



  /**
    * Creates a new StreamDecoder that is functionally equivalent to this codec but returns the specified string from `toString`.
    *
    * @group combinators
    */
  final def withToString(str: => String): StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = self.decode(bits)
    override def toString:String = str
  }

  /**
    * Creates a new StreamDecoder that is functionally equivalent to this codec but returns the specified string from `toString`.
    *
    * @group combinators
    */
  final def withContext(ctx: String): StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = self.decode(bits).mapErr(_.pushContext(ctx))
  }

  /**
    *
    * @group combinators
    */
  final def ~~:[B>:A,C<:B](rhs: StreamDecoder[C]):StreamDecoder[B]= new StreamDecoder[B] {
      def decode(bits: BitVector): StreamDecodeResult[B] = {
        rhs.decode(bits) match {
          case dsr@StreamDecodeResult(_,_,Complete) =>
            dsr.asInstanceOf[StreamDecodeResult[B]].copy(nextStep = Continue(self))
          case o =>
            o
        }
      }
      override def toString:String = s"$self :~~ $rhs"
    }

  /**
    * Creates a new StreamDecoder that uses this decoder until it emits its first element and then
    * creates a new StreamDecoder that is used from that time on.
    */
  def flatPrepend[B](f: A => StreamDecoder[B]): StreamDecoder[B] = new StreamDecoder[B] {
    def decode(bits: BitVector):StreamDecodeResult[B] = { self.decode(bits) match {
      case StreamDecodeResult(rem,Some(first),_) => StreamDecodeResult(rem,None,Continue(f(first)))
    }
    }
  }

  /** Alias for [[StreamDecoder$.chunked]]. */
  final def chunked(size:Long):StreamDecoder[A] = StreamDecoder.chunked(self)(size).withToString(s"$self.chunked(size)")

  /**
    * Alias for [[StreamDecoder$.or(]].
    */
  final def |[B>:A](d: StreamDecoder[B]) = (self or d).withToString(s"$self | $d")

  /**
    * Alias for [[StreamDecoder.or(]].
    */
  final def or[A2>:A](d: StreamDecoder[A2]): StreamDecoder[A2] = StreamDecoder.or(this,d).withToString(s"$self.or($d)")

  /**
    * Converts this stream decoder to a new stream decoder that fails decoding if there are remaining bits after the given stream codec completes.
    * TODO: this does not work
    */
  def complete: StreamDecoder[A] = new StreamDecoder[A] {
    override def toString:String = s"$self.complete"
    override def decode(bits: BitVector): StreamDecodeResult[A] = self.decode(bits) match {
      case StreamDecodeResult(remainder,decoded,Complete) if !remainder.isEmpty =>
        StreamDecodeResult(remainder,decoded,Fail(RemainingBitsAtCompletion(remainder)))
      case StreamDecodeResult(remainder,decoded,Complete) =>
        StreamDecodeResult(remainder,decoded, Continue(StreamDecoder.EOT))
      case o => o
    }
  }

  /**
    * Converts this stream decoder to a `StreamDecoder[B]` using the supplied `A => B`.
    *
    * @group combinators
    */
  def map[B](f:A=>B):StreamDecoder[B] = new StreamDecoder[B] {
    def decode(bits: BitVector): StreamDecodeResult[B] = StreamDecoder.this.decode(bits).map(f)
  }

  def flatMapDecoded[B](f:A=>Option[B]):StreamDecoder[B] = new StreamDecoder[B] {
    def decode(bits: BitVector): StreamDecodeResult[B] = StreamDecoder.this.decode(bits).flatMap(f)
  }


  /** Decode at most `n` values using this `StreamDecoder`. */
  def take(n: Long): StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = self.decode(bits) match {
      //case r@StreamDecodeResult(_,Some(decoded),Continue) =>
      case o => o
    }
    override def toString = s"take($n)"
  }

}

object StreamDecoder {

  final implicit class DecoderOps[A](private val dec:Decoder[A]) extends AnyVal {
    def once:StreamDecoder[A] = StreamDecoder.once(dec)
    def many:StreamDecoder[A] = StreamDecoder.many(dec)
  }

  final implicit class StreamDecoderStringOps(private val ctx:String) extends AnyVal {
    def |[A](d:StreamDecoder[A]):StreamDecoder[A] = d.withContext(ctx)
  }


  /**
    * @group combinators
    */
  def chunked[A](dec:StreamDecoder[A])(chunkSize:Long):StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = {
      if (bits.length < chunkSize) { StreamDecodeResult(bits, None, RequestMoreBits(chunkSize, true, Nil)) }
      else dec.decode(bits) match {
        case StreamDecodeResult(r,d,RequestMoreBits(waitBitCount,soft,ctx)) =>
          StreamDecodeResult(r,d,RequestMoreBits(math.max(chunkSize,waitBitCount) ,soft,ctx))
        case o => o
      }
    }
    override def toString:String = s"chunked($dec)($chunkSize)"
  }

  /**
    * @group combinators
    */
  final def chunkedBytes[A](dec:StreamDecoder[A])(size:Long):StreamDecoder[A] =
    chunked(dec)(size*8).withToString(s"chunkedBytes($dec)($size)")

  /**
    * Gets a stream decoder that decodes the given stream decoder successfully but discards all of it's emitted values.
    * This stream decoder fails if the underlying stream decoder fails.
    *
    * @group combinators
    */
  def expect(d:StreamDecoder[_]): StreamDecoder[Nothing] = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector): StreamDecodeResult[Nothing] = d.decode(bits) match {
      case StreamDecodeResult(remainder,_,Continue(innerD)) => StreamDecodeResult(remainder, None, Continue(expect(innerD)))
      case StreamDecodeResult(remainder,_,Complete) => StreamDecodeResult(remainder, None, Complete)
      case StreamDecodeResult(remainder,_,Fail(f)) => StreamDecodeResult(remainder, None, Fail(f))
      case StreamDecodeResult(remainder,_,w:RequestMoreBits) => StreamDecodeResult(remainder, None, w)
    }
    override def toString = s"expect($d)"
  }

  /**
    * Gets a stream decoder that instantly fails with a [[RemainingBitsAtCompletion]] whenever it is fed with data.
    */
  val EOT = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector): StreamDecodeResult[Nothing] = bits match {
      case b if b.isEmpty => StreamDecodeResult(b, None, Complete)
      case b => StreamDecodeResult(b, None, Fail(RemainingBitsAtCompletion(bits)))
    }
    override def toString = "EOT"
  }

  /**
    * Gets a stream decoder that emits a given element and completes without consuming bits.
    * This stream decoder can never fail.
    *
    * Note: emit requires at least one upstream BitVector to be triggered.
    *
    * @group constructors
    */
  def provide[A](a:A):StreamDecoder[A] =
    new StreamDecoder[A] {
      override def decode(bits: BitVector): StreamDecodeResult[A] =
        StreamDecodeResult(bits, Some(a), Complete)
      override def toString = s"provide($a)"
    }


  /**
    * Gets a StreamDecoder that decodes a single A and completes.
    * This stream decoder fails if the underlying decoder fails.
    * It may reject and wait until sufficient bits are available.
 *
    * @group constructors
    */
  def once[A](dec: =>Decoder[A]) = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = {
      dec.decode(bits) match {
        case s@Successful(DecodeResult(value, remainder)) => StreamDecodeResult(remainder, Some(value), Complete)
        case f@Failure(InsufficientBits(needed,_,ctx)) => StreamDecodeResult(bits, None, RequestMoreBits(needed, soft=false, ctx))
        case f@Failure(e) => StreamDecodeResult(bits, None, Fail(e))
      }
    }
    override def toString = s"once($dec)"
  }

  /**
    * Get a stream decoder that emits nothing and completes immediately without consuming bits.
    * This stream decoder can never fail.
    * @group control
    */
  def halt = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector): StreamDecodeResult[Nothing] = StreamDecodeResult(bits, None, Complete)
    override def toString = s"halt"
  }

  /**
    * Gets a stream codec that consumes a given number of bits from the buffer.
    * It may reject and wait until sufficient bits are available.
    * @group control
    */
  def ignore(bitsToSkip: Long, allowTermination:Boolean = false):StreamDecoder[Nothing] = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector) =
      if (bits.length >= bitsToSkip) {
        StreamDecodeResult(bits.drop(bitsToSkip), None, Complete)
      } else {
        StreamDecodeResult(bits, None, RequestMoreBits(bitsToSkip, soft = !allowTermination, Nil))
      }
    override def toString = s"ignore($bitsToSkip, $allowTermination)"
  }

  /**
    * Gets a stream codec that consumes a given number of bytes from the buffer.
    * It may reject and wait until sufficient bits are available.
    * @group control
    */
  def ignoreBytes(bytes:Long, allowTermination:Boolean = true):StreamDecoder[Nothing] =
    ignore(bytes*8, allowTermination).withToString(s"ignoreBytes($bytes,$allowTermination)")

  /**
    * Get a stream codec
    * @group combinators
    */
  def drop(d:StreamDecoder[_]):StreamDecoder[Nothing] = d.flatMapDecoded(_=>None).withToString(s"drop($d)")

  /**
    * Gets a `StreamDecoder` that immediately fails with the given err without consuming bits.
    * @group control
    */
  def fail(err: =>Err) = new StreamDecoder[Nothing] {
    override def decode(bits: BitVector) = StreamDecodeResult(bits, None, Fail(err))
    override def toString = s"fail($err)"
  }

  /**
    * Run the given `StreamDecoder` using only the first `numberOfBits` bits of
    * the current stream, then advance the cursor by that many bits on completion.
 *
    * @group combinators
    */
  def isolate[A](numberOfBits: Long)(d: StreamDecoder[A]): StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = {
      ???
    }
    override def toString = s"isolate($numberOfBits)($d)"
  }

  /**
    * Run the given `StreamDecoder` using only the first `numberOfBits` bits of
    * the current stream, then advance the cursor by that many bits on completion.
 *
    * @group combinators
    */
  def isolateBytes[A](numberOfBytes: Long)(d: StreamDecoder[A]): StreamDecoder[A] = new StreamDecoder[A] {
    val isolated = isolate(numberOfBytes*8)(d)
    override def decode(bits: BitVector): StreamDecodeResult[A] = isolated.decode(bits)
    override def toString = s"isolateBytes($numberOfBytes)($d)"
  }

  /**
    * Runs `s1`, then runs `s2` if `s1` emits no elements.
    * Fails if `s1` or `s2` fails.
    * Completes if either `this` completes after emitting an element or `d` completes.
    *
    * Example: `or(tryOnce(codecs.int32), once(codecs.uint32))`.
    * This function does no backtracking of its own; backtracking
    * should be handled by `s1`.
    *
    * @group combinators
    */
  def or[A](s1: StreamDecoder[A], s2: StreamDecoder[A]):StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = s1.decode(bits) match {
      case StreamDecodeResult(r,None,Complete) => s2.decode(r)
      // TODO: what happens if s1 has emitted a value already in an earlier step?
      case o => o
    }
    override def toString = s"or($s1, $s2)"
  }


  /**
    * Gets a stream coded that repeats to decode elements from the given decoder.
    * This stream decoder fails if the underlying decoder fails.
    * It may reject and wait until sufficient bits are available.
 *
    * @group constructors
    */
  def many[A](d:Decoder[A]):StreamDecoder[A] = many(once(d))

  /**
    * Like [[once]], but halts normally and leaves the
    * input unconsumed in the event of a decoding error.
    * @group constructors
    */
  def tryOnce[A](dec: Decoder[A]): StreamDecoder[A] = new StreamDecoder[A] {
    val sdec = once(dec)
    override def decode(bits: BitVector): StreamDecodeResult[A] = sdec.decode(bits) match {
      case StreamDecodeResult(_,_,Fail(e)) => StreamDecodeResult(bits,None,Complete)
      case o => o
    }
    override def toString = s"tryOnce($dec)"
  }


  /**
    * Codec that encodes/decodes `N` elements of `A` from a stream.
    *
    * The number of elements is decoded using `countCodec` and then that number of elements
    * are decoded using `valueCodec`. Any remaining bits are returned.
    *
    * Note: when the count is known statically, use `repeat(..., count)`.
    *
    * @param dec codec to encode/decode a single element of the sequence
    * @group combinators
    */
  def many[A](dec:StreamDecoder[A], times:Long):StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = {
      if (times <= 0) StreamDecodeResult(bits,None,Complete)
      dec.decode(bits) match {
        case sdr@StreamDecodeResult(_,_,Complete) => sdr.copy(nextStep = Continue(many(dec, times-1)))
        case o => o
      }
    }
    override def toString = s"many($dec, $times)"
  }


  def many[A](s:StreamDecoder[A]):StreamDecoder[A] = new StreamDecoder[A] {
    override def decode(bits: BitVector): StreamDecodeResult[A] = {
      s.decode(bits) match {
        case sdr@StreamDecodeResult(_,_,Complete) => sdr.copy(nextStep = Continue(many(s)))
        case o => o
      }
    }
    override def toString = s"many($s)"
  }


  /**
    * @group combinators
    */
  def streamOfN[A](countCodec: Decoder[Long], valueCodec: StreamDecoder[A]): StreamDecoder[A] = new StreamDecoder[A] {
    val composed = once(countCodec).flatPrepend(count => many(valueCodec,count))
    override def decode(bits: BitVector): StreamDecodeResult[A] = composed.decode(bits)
    override def toString = s"streamOfN($countCodec, $valueCodec)"
  }

}
