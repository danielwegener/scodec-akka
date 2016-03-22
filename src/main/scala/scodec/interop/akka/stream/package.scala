package scodec.interop.akka

/*
import akka.stream.FlowShape
import akka.stream.stage.GraphStage
import scodec.bits.BitVector
import scodec.interop.akka.stream.decode.{StreamDecoderStage, StreamDecoder}
*/


/**
 * Provides combinators to lift a codec into a endocing and decoding flows.
 */
package object stream {

  //def decode[A](decoder:StreamDecoder[A]):GraphStage[FlowShape[BitVector, A]] = new StreamDecoderStage[A](decoder)

}
