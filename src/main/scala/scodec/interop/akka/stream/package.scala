package scodec.interop.akka

import scodec.interop.akka.stream.decode.{StreamDecoderStage, StreamDecoder}

import scala.language.implicitConversions


/**
 * Provides combinators to lift a codec into a endocing and decoding flows.
 */
package object stream {

  implicit def toStreamDecoderStage[T](sdec:StreamDecoder[T]): StreamDecoderStage[T] = new StreamDecoderStage[T](sdec)

  //def decode[A](decoder:StreamDecoder[A]):GraphStage[FlowShape[BitVector, A]] = new StreamDecoderStage[A](decoder)

}
