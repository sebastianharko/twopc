package app

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{complete, get, path, post, _}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import com.lightbend.cinnamon.akka.CinnamonMetrics
import com.lightbend.cinnamon.metric._
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.duration._
import scala.language.postfixOps

class Main

object Main extends App {

  implicit val system = ActorSystem("minimal")

  val logging = Logging(system, classOf[Main])

  val votingTimeoutCounter: Counter = CinnamonMetrics(system).createCounter("coordinatorVotingTimeout")
  val commitTimeoutCounter: Counter = CinnamonMetrics(system).createCounter("coordinatorCommitTimeout")
  val accountTimeoutCounter: Counter = CinnamonMetrics(system).createCounter("accountTimeout")

  val accounts = Sharding.accounts(system, accountTimeoutCounter)

  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher

  import de.heikoseeberger.akkahttpjson4s.Json4sSupport._

  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats

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
        complete {
          accounts.ask(GetBalance(accountId))(queryTimeout, ActorRef.noSender).mapTo[Int].map(r => Map("amount" -> r))
        }
      }
    }
  } ~ path("deposit" /  Segment / IntNumber) {
      case (accountId, amount) =>
        post {
          complete {
            (accounts ? ChangeBalance(accountId, amount)).map {
              case Accepted(_) => Map("completed" -> true)
              case Rejected(_) => Map("completed" -> false)
              case AccountStashOverflow(_) => Map("completed" -> false)
            }
          }
          }
  } ~ path("withdraw" / Segment / IntNumber) {
     case (accountId, amount) =>
       post {
         complete {
           (accounts ? ChangeBalance(accountId, - amount)).map {
             case Accepted(_) => Map("completed" -> true)
             case Rejected(_) => Map("completed" -> false)
           } }
         }
  } ~ path("transaction" / Segment / Segment / Segment / IntNumber) {
    case (transactionId, sourceAccountId, destinationAccountId, amount) => {
      post {
        complete {
          val coordinator = system.actorOf(Props(new Coordinator(accounts, votingTimeoutCounter, commitTimeoutCounter)), transactionId)
          (coordinator ? MoneyTransaction(transactionId, sourceAccountId, destinationAccountId, amount)).map {
            case Accepted(_) => Map("completed" -> true)
            case Rejected(_) => Map("completed" -> false)
          }

        }
      }
    }

  }

  val bindingFuture = Http().bindAndHandle(route, scala.sys.env.getOrElse("POD_IP", "0.0.0.0"), 8080)


}

