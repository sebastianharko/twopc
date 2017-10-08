package app

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._
import io.gatling.http.protocol.HttpProtocolBuilder

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

class BasicSimulation extends Simulation {

  def accountId: String = Random.nextInt(20000).toString

  def amount: Int = Random.nextInt(100)

  def action: String = if (Random.nextBoolean()) {
    "deposit"
  } else {
    "withdraw"
  }

  val httpConf: HttpProtocolBuilder = http

  val scn: ScenarioBuilder = scenario("Only Scenario").exec(
    http("request").post(s"http://localhost:8080/$action/$accountId/$amount")
  )

  setUp(scn.inject(constantUsersPerSec(100) during(10 minutes))).protocols(httpConf)

}

