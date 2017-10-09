package app

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class BasicSimulation extends Simulation {

  val feeder : Iterator[Map[String, Any]] = Iterator.continually {
    val from = Random.nextInt(400).toString
    val to = Random.nextInt(400).toString
    Map("from" -> from, "to" -> to)
  }

 val host = sys.env.getOrElse("APP_IP_PORT", "localhost:8080")

  def action: String = "transaction"

  val httpConf: HttpProtocolBuilder = http

  val scn1: ScenarioBuilder = scenario("First Scenario").feed(feeder)
      .exec(http("transaction").post(session => s"http://$host/$action/${session("from").as[String]}/${session("to").as[String]}/0").check(substring("true")))
      .exec(http("query-1").get(session => s"http://$host/query/${session("from").as[String]}"))
      .exec(http("query-2").get(session => s"http://$host/query/${session("to").as[String]}")
  )


  setUp(scn1.inject(constantUsersPerSec(400) during(2 minutes))).protocols(httpConf)

}

