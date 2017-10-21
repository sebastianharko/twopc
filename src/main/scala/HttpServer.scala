package app

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Timers}
import akka.cluster.sharding.ShardRegion
import akka.cluster.sharding.ShardRegion.CurrentShardRegionState
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{complete, get, path, post, _}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import com.lightbend.cinnamon.akka.CinnamonMetrics
import com.lightbend.cinnamon.akka.http.scaladsl.server.Endpoint
import com.lightbend.cinnamon.metric._

import scala.concurrent.duration._
import scala.language.postfixOps

import app.messages._

class Main

object Main extends App {

  implicit val system = ActorSystem("minimal")

  val logging = Logging(system, classOf[Main])

  val shardCounter = CinnamonMetrics(system).createGaugeLong("shards")
  val entityCounter = CinnamonMetrics(system).createGaugeLong("entities")
  val votingTimeouts = CinnamonMetrics(system).createRate("coordinatorVotingTimeout")
  val commitTimeouts = CinnamonMetrics(system).createRate("coordinatorCommitTimeout")
  val accountTimeouts = CinnamonMetrics(system).createRate("accountTimeout")
  val accountStashHitCounter = CinnamonMetrics(system).createCounter("accountStashHit")
  val activeTransactions = CinnamonMetrics(system).createGaugeLong("transactions")

  val accounts = Sharding.accounts(system, accountTimeouts, accountStashHitCounter)

  implicit val blockingDispatcher = system.dispatchers.lookup("my-blocking-dispatcher")


  implicit val materializer = ActorMaterializer()

  val StandardTimeout = sys.env.get("HTTP_TIMEOUT").map(_.toLong milliseconds).getOrElse(1500 milliseconds)
  implicit val standardTimeout = akka.util.Timeout(StandardTimeout)

  val QueryTimeout = sys.env.get("QUERY_HTTP_TIMEOUT").map(_.toLong milliseconds).getOrElse(350 milliseconds)
  val queryTimeout = akka.util.Timeout(QueryTimeout)

  logging.info("PASSIVATE_ACCOUNT is {}", Sharding.PassivateAfter)
  logging.info("NUM_SHARDS is {}", Sharding.NumShards)
  logging.info("HTTP_TIMEOUT is {}",  StandardTimeout._1)
  logging.info("QUERY_HTTP_TIMEOUT is {}", QueryTimeout._1)
  logging.info("ACCOUNT_TIMEOUT is {}", AccountActor.CommitOrAbortTimeout._1)
  logging.info("VOTING_TIMEOUT is {}", Coordinator.TimeOutForVotingPhase._1)
  logging.info("COMMIT_TIMEOUT is {}", Coordinator.TimeOutForCommitPhase._1)

  val route = path("query" / Segment) {
    accountId => {
      get {
        Endpoint.withName("CustomerAccount") {
          complete {
            accounts.ask(GetBalance(accountId))(queryTimeout, ActorRef.noSender).mapTo[Int].map((r: Int) => r.toString)
          }
        }
      }
    }
  } ~ path("deposit" /  Segment / IntNumber) {
      case (accountId, amount) =>
        post {
          complete {
            (accounts ? ChangeBalance(accountId, amount)).map {
              case Accepted(_) => "true"
              case Rejected(_) => "false"
              case AccountStashOverflow(_) => "false"
            }
          }
          }
  } ~ path("withdraw" / Segment / IntNumber) {
     case (accountId, amount) =>
       post {
         complete {
           (accounts ? ChangeBalance(accountId, - amount)).map {
             case Accepted(_) => "true"
             case Rejected(_) => "false"
           } }
         }
  } ~ path("transaction" / Segment / Segment / Segment / IntNumber) {
    case (transactionId, sourceAccountId, destinationAccountId, amount) => {
      post {
        complete {
          val coordinator = system.actorOf(Props(new Coordinator(accounts)), transactionId)
          (coordinator ? MoneyTransaction(transactionId, sourceAccountId, destinationAccountId, amount)).map {
            case Accepted(_) => "true"
            case Rejected(_) => "false"
          }

        }
      }
    }

  }

  val bindingFuture = Http().bindAndHandle(route, scala.sys.env.getOrElse("POD_IP", "0.0.0.0"), 8080)


}

