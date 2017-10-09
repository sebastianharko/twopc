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
    val from = Random.nextInt(1000000).toString
    val to = Random.nextInt(1000000).toString
    Map("from" -> from, "to" -> to)
  }

  def accountId: String = Random.nextInt().toString

  def amount: Int = Random.nextInt(100)

  def action: String = "transaction"

  val httpConf: HttpProtocolBuilder = http

  val scn: ScenarioBuilder = scenario("Only Scenario").feed(feeder).exec(
    http("request").post(session => s"http://localhost:8080/$action/${session("from").as[String]}/${session("to").as[String]}/0")
  )

  setUp(scn.inject(constantUsersPerSec(10) during(1 minutes))).protocols(httpConf)

}

