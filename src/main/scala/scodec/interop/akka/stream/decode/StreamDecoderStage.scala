package scodec.interop.akka.stream.decode

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import scodec.bits.BitVector
import scodec.interop.akka.stream.DecodingError

import scala.annotation.tailrec

class StreamDecoderStage[T](dec:StreamDecoder[T]) extends GraphStage[FlowShape[BitVector, T]] {


  private val bytesIn = Inlet[BitVector]("bytesIn")
  private val objOut = Outlet[T]("objOut")
  final override val shape = FlowShape(bytesIn, objOut)
  override def initialAttributes = Attributes.name("StreamDecoderStage")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new DecodingLogic

  private val NoWait = RequestMoreBits(0,true,Nil)

  private class DecodingLogic extends GraphStageLogic(shape) {
    private var current:StreamDecoder[T] = dec
    private var buffer = BitVector.empty
    private var waitFor:RequestMoreBits = NoWait
    private var allowTermination = false
    private var upstreamFinished = false

    private def emitOut(t:T) = {
      emit(objOut,t)
    }

    private def pullUpstream() = {
      if (!hasBeenPulled(bytesIn) && !isClosed(bytesIn)) pull(bytesIn)
      else if (!waitFor.soft) fail(objOut, DecodingError(InsufficientBitsAtCompletion(waitFor.bitCount, buffer.size, waitFor.context)))
    }

    /**
      * A decoding stage should doParse
      * - as long as it emits elements
      * - as long as it consumes input
      *
      * A decoding stage should pull(bytesIn)
      * - when the buffer is empty
      * - when an StreamDecoder requests more bits
      *
      * A decoding stage should fail
      * - if onUpstreamFinish but the StreamDecoder is not ready for termination
      *
      */
    @tailrec private def doParse(): Unit =
        current.decode(buffer) match {
          case sdr@StreamDecodeResult(remainder, decoded, w:RequestMoreBits) =>
            buffer = remainder
            waitFor = w
            decoded.foreach(emitOut)
            pullUpstream()
          case sdr@StreamDecodeResult(_, _, Fail(err)) =>
            failStage(new DecodingError(err))
          case sdr@StreamDecodeResult(remainder, decoded, Complete) =>
            buffer = remainder
            current = StreamDecoder.halt
            decoded.foreach(emitOut)
            completeStage()
          case sdr@StreamDecodeResult(remainder, decoded, Continue(next)) =>
            buffer = remainder
            current = next
            decoded.foreach(emitOut)
            if (remainder.isEmpty) {
              pullUpstream()
            } else {
              doParse()
            }

        }


    override def preStart(): Unit = pullUpstream()


    setHandler(objOut, eagerTerminateOutput)
    setHandler(bytesIn, new InHandler {
      override def onPush(): Unit = {
        val grabbed = grab(bytesIn)
        buffer ++= grabbed
        if (waitFor.bitCount > buffer.length) {
          pull(bytesIn)
        } else {
          waitFor = NoWait
          doParse()
        }
      }
      override def onUpstreamFinish(): Unit = {
        upstreamFinished = true
        if (waitFor.bitCount <= buffer.size || waitFor.soft) completeStage()
        else if (buffer.size > 0) doParse()
        else fail(objOut, DecodingError(InsufficientBitsAtCompletion(waitFor.bitCount, buffer.size, waitFor.context)))
      }
    })
  }

}
