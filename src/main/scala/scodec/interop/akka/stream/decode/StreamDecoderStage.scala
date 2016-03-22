package scodec.interop.akka.stream.decode

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import scodec.bits.BitVector
import scodec.interop.akka.stream.DecodingError

import scala.annotation.tailrec

class StreamDecoderStage[T](dec:StreamDecoder[T]) extends GraphStage[FlowShape[BitVector, T]]{
  private val bytesIn = Inlet[BitVector]("bytesIn")
  private val objOut = Outlet[T]("objOut")
  final override val shape = FlowShape(bytesIn, objOut)
  override def initialAttributes = Attributes.name("StreamDecoderStage")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new DecodingLogic

  private class DecodingLogic extends GraphStageLogic(shape) {
    private var current = dec
    private var buffer = BitVector.empty
    private var waitFor:Long = 0
    private var waitForContext:List[String] = Nil

    private def emitOut(t:T) = emit(objOut,t)

    @tailrec private def doParse(): Unit = {
        current.decode(buffer) match {
          case StreamDecodeResult(remainder, decoded, Wait(bitCount,ctx)) =>
            pull(bytesIn)
            decoded.foreach(emitOut)
            buffer = remainder
            waitFor = bitCount
            waitForContext = ctx
          case StreamDecodeResult(_, _, Fail(err)) =>
            failStage(new DecodingError(err))
          case StreamDecodeResult(remainder, decoded, Complete) =>
            buffer = remainder
            current = StreamDecoder.halt
            decoded.foreach(emitOut)
            completeStage()
          case StreamDecodeResult(remainder, decoded, Continue(next)) =>
            buffer = remainder
            current = next
            decoded.foreach(emitOut)
            doParse()
        }
    }

    override def preStart(): Unit = pull(bytesIn)

    setHandler(objOut, eagerTerminateOutput)
    setHandler(bytesIn, new InHandler {
      override def onPush(): Unit = {
        buffer ++= grab(bytesIn)
        if (waitFor > buffer.length) {
          pull(bytesIn)
        } else {
          waitFor = 0
          waitForContext = List.empty
          doParse()
        }
      }
      override def onUpstreamFinish(): Unit =
        if (buffer.isEmpty && waitFor == 0L) completeStage()
        else fail(objOut, DecodingError(InsufficientBitsAtCompletion(waitFor, buffer.size, waitForContext)))
    })
  }

}
