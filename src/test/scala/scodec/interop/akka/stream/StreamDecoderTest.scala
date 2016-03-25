package scodec.interop.akka.stream

import akka.actor.ActorSystem
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSource, TestSink}
import scodec.codecs._
import scodec.bits._
import scodec.interop.akka.stream.decode._

class StreamDecoderTest extends StreamDecoderSuite(ActorSystem("StreamDecoderTest")) {

  override def afterAll():Unit = {
    shutdown(system)
  }


  import StreamDecoder._

  "StreamDecoderDsl" should {

    "|" in {
      val _ = decodeProbe(List(bin"11", hex"ff".bits),  drop(tryOnce(constant(bin"01"))) | drop(tryOnce(constant(bin"11"))) ~~: uint8.once  )
        .request(1)
        .expectNext(255)
    }

    "once" in {
       val _ = decodeProbe(List(bin"1"), bool.once)
        .request(1)
        .expectNext(true)
        .expectComplete()
    }

    "repeat" in {
      val _ = decodeProbe(List(bin"10101010"), bool.once)
        .request(1)
        .expectNext(true)
        .expectComplete()
    }

    "~~" in {
      val _ = decodeProbe(List(bin"100101010100011", bin"1"), bool.once ~~: bool.once)
        .request(2)
        .expectNext(true, false)
        .expectComplete()
    }

    "~~ multiple times" in {
      val _ = decodeProbe[Int](List(hex"10203000".bits), uint4L.once ~~: uint8L.once ~~: int16L.once)
        .request(3)
        .expectNext(1, 2, 3)
        .expectComplete()
    }

    "emit" in {
      val _ = decodeProbe(List(bin""), provide("foo"))
        .request(1)
        .expectNext("foo")
        .expectComplete()
    }

    "halt" in {
      val (source, sink) = TestSource.probe[BitVector].via(new StreamDecoderStage(halt))
        .toMat(TestSink.probe[String])(Keep.both).run()
      source.ensureSubscription()
      sink.ensureSubscription()
      source.sendNext(BitVector.empty)
      source.expectCancellation()
      val _ = sink.request(1).expectComplete()
    }

    "ignore" in {
      val r = decodeProbe[Unit](List(hex"f0f0".bits,hex"affeaf".bits, hex"feaffe".bits ,hex"f1f1".bits),
        constant(hex"f0f0").once ~~: ignore(48, allowTermination = false) ~~: constant(hex"f1f1").once)
        .request(2)
        .expectNext((),())
        .expectComplete()
    }
  }

  "StreamDecoderStage" should {

    "decode many" in {
      val _ = decodeProbe[Long]((1L to 10000L).map(uint32.encode).map(_.require), many(uint32))
        .ensureSubscription()
        .request(10000)
        .expectNextN(1L to 10000L)
        .expectComplete()
    }

    "allow more demand than elements available" in {
      val _ = decodeProbe(List(hex"01020304".bits),many(uint8))
        .request(5)
        .expectNext(1,2,3,4)
          .expectComplete()
    }

    "decode strict codec over multiple chunks" in {
      val _ = decodeProbe(List(hex"01".bits, hex"02".bits), many(uint16))
        .request(1)
        .expectNext(258)
        .expectComplete()
    }

    "decode stream codec over multiple chunks" in {
      val _ = decodeProbe(List(hex"010203".bits, hex"04".bits), many(uint8))
        .request(4)
        .expectNext(1,2,3,4)
        .expectComplete()
    }

    "fail stream if upstream completes and streamcodec is not yet complete" in {
      val _ = decodeProbe(List(hex"01".bits), many(uint16))
        .request(1)
        .expectError(DecodingError(InsufficientBitsAtCompletion(16,8,List.empty)))
    }
  }
}
