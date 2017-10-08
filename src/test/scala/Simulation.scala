package app

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import io.gatling.http.Predef._

import scala.concurrent.duration._
import scala.util.Random

class BasicSimulation extends Simulation {

  def accountId = Random.nextInt(20000).toString

  def amount = Random.nextInt(100)

  def action = Random.nextBoolean() match {
    case true => "deposit"
    case false => "withdraw"
  }

  val httpConf = http

  val scn: ScenarioBuilder = scenario("Only Scenario").exec(
    http("request").post(s"http://localhost:8080/$action/$accountId/$amount")
  )

  setUp(scn.inject(constantUsersPerSec(100) during(10 minutes))).protocols(httpConf)

}

