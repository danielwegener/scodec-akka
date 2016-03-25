package scodec.interop.akka.stream

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scodec.bits.BitVector
import scodec.interop.akka.stream.decode.StreamDecoder

abstract class StreamDecoderSuite(s:ActorSystem) extends TestKit(s) with WordSpecLike with Matchers with BeforeAndAfterAll  {

  implicit val _implicitSystem = s

  implicit val materializer = ActorMaterializer()

  def decodeProbe[T](bits:collection.immutable.Iterable[BitVector], decoder:StreamDecoder[T]):Probe[T] =
    Source[BitVector](bits).via(decoder)
    .runWith(TestSink.probe[T])



}
