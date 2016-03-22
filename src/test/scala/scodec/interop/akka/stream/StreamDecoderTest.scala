package scodec.interop.akka.stream

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestKit
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import scodec.Err.InsufficientBits
import scodec.codecs._
import scodec.bits._
import scodec.interop.akka.stream.decode.{StreamDecoder, StreamDecoderStage}

class StreamDecoderTest extends TestKit(ActorSystem("StreamDecoderTest")) with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll():Unit = {
    shutdown(system)
  }

  implicit val materializer = ActorMaterializer()

  import StreamDecoder._

  "StreamDecoderDsl" should {

    "once" in {
      import StreamDecoder._

       val r = Source(List(bin"10101010")).via(new StreamDecoderStage(once(bool)))
        .runWith(TestSink.probe[Boolean])
        .request(1)
        .expectNext(true)
        .expectComplete()
    }

    "~" in {
      val r = Source(List(bin"100101010100011", bin"1")).via(new StreamDecoderStage(once(bool) ~ once(bool)))
        .runWith(TestSink.probe[Boolean])
        .request(2)
        .expectNext(true, false)
        .expectComplete()
    }

    "emit" in {
      val ignore = Source(List(bin"")).via(new StreamDecoderStage(emit("foo")))
        .runWith(TestSink.probe[String])
        .request(2)
        .expectNext("foo")
        .expectComplete()
    }
    "halt" in {
      val ignore = Source(List(bin"1001010101001")).via(new StreamDecoderStage(halt))
        .runWith(TestSink.probe[String])
        .request(1)
        .expectComplete()
    }


  }

  "ByteStringDecoder" should {

    "decode repeat" in {
      val sdec = StreamDecoder.repeat(uint32)
      val stage = new StreamDecoderStage(sdec)
      val sourceUnderTest:Source[Long,_] = Source(1L to 4L).map(uint32.encode).map(_.require).via(stage)

      val p = sourceUnderTest
        .runWith(TestSink.probe[Long])
        .ensureSubscription()
        .request(4)
        .expectNext(1L,2L,3L,4L)
        .expectComplete()

    }

    "decode framed" in {
      val sdec = StreamDecoder.repeat(uint8)
      val stage = new StreamDecoderStage(sdec)
      val sourceUnderTest:Source[Int,_] = Source[BitVector](List(hex"0102".bits, hex"0304".bits)).via(stage)

      val p = sourceUnderTest
        .runWith(TestSink.probe[Int])
        .request(4)
        .expectNext(1,2,3,4)
        .expectComplete()

    }

    "backpressure framed" in {
      val sdec = StreamDecoder.repeat(uint8)
      val stage = new StreamDecoderStage(sdec)
      val sourceUnderTest:Source[Int,_] = Source[BitVector](List(hex"01020304".bits)).via(stage)

      val p = sourceUnderTest
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNext(1,2)

    }

    "decode over multiple chunks" in {
      val sdec = StreamDecoder.repeat(uint16)
      val stage = new StreamDecoderStage(sdec)
      val sourceUnderTest:Source[Int,_] = Source[BitVector](List(hex"01".bits, hex"02".bits)).via(stage)

      val p = sourceUnderTest
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(258)
        .expectComplete()

    }

    "fail stream if incomplete" in {
      val sdec = StreamDecoder.repeat(uint16)
      val stage = new StreamDecoderStage(sdec)
      val sourceUnderTest:Source[Int,_] = Source[BitVector](List(hex"01".bits)).via(stage)

      val p = sourceUnderTest
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectError(DecodingError(InsufficientBits(16,8,List.empty)))
    }




  }

}
