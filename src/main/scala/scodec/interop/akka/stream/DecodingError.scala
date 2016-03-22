package scodec.interop.akka.stream

import scodec.Err

case class DecodingError(err: Err) extends Exception(err.messageWithContext)

