package rd

object Norm {

  import scala.collection.mutable

  var m = mutable.Map[String, Long]()
  (1 to 5 * 60 * 10000).foreach {
    i => {
      if (i % 6000 == 0) { println("at " + i); Thread.sleep(100); }
      val x = java.util.UUID.randomUUID().toString.replace("-", "").substring(0, 15)
      val key = (Math.abs(x.hashCode) % 28).toString
      if (m.get(key).isEmpty) {
        m += key -> 0L
      }
      m(key) = m(key) + 1
    }
  }


  m.foreach {
    case (key, value) => println(key + "->" + value)
  }
}