package app

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

import scala.concurrent.duration._
import scala.language.postfixOps

class TransactionTwoQueries extends Simulation {

  val feeder: Iterator[Map[String, Any]] = Iterator.continually {
    val transactionId = java.util.UUID.randomUUID().toString.replace("-", "").substring(0, 15)
    val from = java.util.UUID.randomUUID().toString.replace("-", "").substring(0, 15)
    val to = java.util.UUID.randomUUID().toString.replace("-", "").substring(0, 15)
    Map("from" -> from, "to" -> to, "transactionId" -> transactionId)
  }

  val host = sys.env.getOrElse("APP_IP_PORT", "localhost:8080")
  val users = sys.env.get("USERS").map(_.toInt).getOrElse(50)
  val time = sys.env.get("PERIOD").map(_.toInt).getOrElse(10)


  def action: String = "transaction"

  val httpConf: HttpProtocolBuilder = http

  val scn1: ScenarioBuilder = scenario("One transaction and two queries").feed(feeder)
      .exec(http("transaction").post(session => s"http://$host/$action/${session("transactionId").as[String]}/${session("from").as[String]}/${session("to").as[String]}/0"))
      .exec(http("query-1").get(session => s"http://$host/query/${session("from").as[String]}"))
      .exec(http("query-2").get(session => s"http://$host/query/${session("to").as[String]}"))

  setUp(scn1.inject(constantUsersPerSec(users) during (time minutes))).protocols(httpConf)

}


class Ping extends Simulation {

  val host = sys.env.getOrElse("APP_IP_PORT", "localhost:8080")
  val users = sys.env.get("USERS").map(_.toInt).getOrElse(50)
  val time = sys.env.get("PERIOD").map(_.toInt).getOrElse(10)

  val scn1: ScenarioBuilder = scenario("Ping")
      .exec(http("ping").get(s"http://$host/ping"))

  val httpConf: HttpProtocolBuilder = http

  setUp(scn1.inject(constantUsersPerSec(users) during (time minutes))).protocols(httpConf)

}

class TransactionOnly extends Simulation {

  val feeder: Iterator[Map[String, Any]] = Iterator.continually {
    val transactionId = java.util.UUID.randomUUID().toString.replace("-", "").substring(0, 15)
    val from = java.util.UUID.randomUUID().toString.replace("-", "").substring(0, 15)
    val to = java.util.UUID.randomUUID().toString.replace("-", "").substring(0, 15)
    Map("from" -> from, "to" -> to, "transactionId" -> transactionId)
  }

  val host = sys.env.getOrElse("APP_IP_PORT", "localhost:8080")
  val users = sys.env.get("USERS").map(_.toInt).getOrElse(50)
  val time = sys.env.get("PERIOD").map(_.toInt).getOrElse(10)


  def action: String = "transaction"

  val httpConf: HttpProtocolBuilder = http

  val scn1: ScenarioBuilder = scenario("One transaction").feed(feeder)
      .exec(http("transaction").post(session => s"http://$host/$action/${session("transactionId").as[String]}/${session("from").as[String]}/${session("to").as[String]}/0"))

  setUp(scn1.inject(constantUsersPerSec(users) during (time minutes))).protocols(httpConf)

}

