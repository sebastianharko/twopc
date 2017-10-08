package app

import java.nio.charset.Charset

import akka.actor.ExtendedActorSystem
import akka.event.Logging
import akka.serialization.Serializer
import org.json4s.DefaultFormats

class EventSerialization(actorSystem: ExtendedActorSystem) extends Serializer {

  import org.json4s.jackson.Serialization.{read, write}
  val UTF8: Charset = Charset.forName("UTF-8")

  implicit val formats = DefaultFormats

  // Completely unique value to identify this implementation of Serializer, used to optimize network traffic.
  // Values from 0 to 16 are reserved for Akka internal usage.
  // Make sure this does not conflict with any other kind of serializer or you will have problems
  override def identifier: Int = 90020001

  override def includeManifest = true

  override def fromBinary(bytes: Array[Byte], manifestOpt: Option[Class[_]]): AnyRef = {
    implicit val manifest = manifestOpt match {
      case Some(x) => Manifest.classType(x)
      case None => Manifest.AnyRef
    }
    val str = new String(bytes, UTF8)
    val result = read(str)
    result
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    val dat = write(o).getBytes(UTF8)
    dat
  }
}
