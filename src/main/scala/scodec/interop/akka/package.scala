package scodec.interop

import scodec.bits.ByteVector

import _root_.akka.util.ByteString

package object akka {

  implicit class EnrichedByteString(val value: ByteString) extends AnyVal {
    def toByteVector: ByteVector = ByteVector(value.asByteBuffer)
  }

  implicit class EnrichedByteVector(val value: ByteVector) extends AnyVal {
    def toByteString: ByteString = ByteString(value.toByteBuffer)
  }
}