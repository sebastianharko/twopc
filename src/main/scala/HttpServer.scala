package app

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, get, path, post, _}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import app.messages._

import scala.concurrent.duration._
import scala.language.postfixOps

class Main

object Main extends App {

  implicit val system = ActorSystem("minimal")

  val logging = Logging(system, classOf[Main])

  if (Cluster(system).selfRoles.contains("ACCOUNT")) {
    logging.info(
      """
        |    ___       ______   ______   ______    __    __  .__   __. .___________.    _______.
        |    /   \     /      | /      | /  __  \  |  |  |  | |  \ |  | |           |   /       |
        |   /  ^  \   |  ,----'|  ,----'|  |  |  | |  |  |  | |   \|  | `---|  |----`  |   (----`
        |  /  /_\  \  |  |     |  |     |  |  |  | |  |  |  | |  . `  |     |  |        \   \
        | /  _____  \ |  `----.|  `----.|  `--'  | |  `--'  | |  |\   |     |  |    .----)   |
        |/__/     \__\ \______| \______| \______/   \______/  |__| \__|     |__|    |_______/
        |
        |
        |
      """.stripMargin)

    logging.info("NUM_SHARDS_ACCOUNTS is {}", AccountActor.NumShards)
    logging.info("PASSIVATE_ACCOUNT is {}", AccountActor.PassivateAfter)
    logging.info("ACCOUNT_TIMEOUT is {}", AccountActor.CommitOrAbortTimeout._1)

    AccountActor.accountsShardRegion(system)

  }

  if (Cluster(system).selfRoles.contains("COORDINATOR")) {
    logging.info(
      """
        |  ______   ______     ______   .______       _______   __  .__   __.      ___   .___________.  ______   .______          _______.
        | /      | /  __  \   /  __  \  |   _  \     |       \ |  | |  \ |  |     /   \  |           | /  __  \  |   _  \        /       |
        ||  ,----'|  |  |  | |  |  |  | |  |_)  |    |  .--.  ||  | |   \|  |    /  ^  \ `---|  |----`|  |  |  | |  |_)  |      |   (----`
        ||  |     |  |  |  | |  |  |  | |      /     |  |  |  ||  | |  . `  |   /  /_\  \    |  |     |  |  |  | |      /        \   \
        ||  `----.|  `--'  | |  `--'  | |  |\  \----.|  '--'  ||  | |  |\   |  /  _____  \   |  |     |  `--'  | |  |\  \----.----)   |
        | \______| \______/   \______/  | _| `._____||_______/ |__| |__| \__| /__/     \__\  |__|      \______/  | _| `._____|_______/
        |
        |
        |
      """.stripMargin)

    logging.info("NUM_SHARDS_COORD is {}", Coordinator.NumShards)
    logging.info("PASSIVATE_COORD is {}", Coordinator.PassivateAfter)
    logging.info("VOTING_TIMEOUT is {}", Coordinator.TimeOutForVotingPhase._1)
    logging.info("COMMIT_TIMEOUT is {}", Coordinator.TimeOutForCommitPhase._1)

    val proxyToAccounts: ActorRef = AccountActor.proxyToShardRegion(system)
    Coordinator.coordinatorShardRegion(system, proxyToAccounts)

  }

  if (Cluster(system).selfRoles.contains("HTTP")) {
    logging.info(
      """
        | __    __  .___________.___________..______
        ||  |  |  | |           |           ||   _  \
        ||  |__|  | `---|  |----`---|  |----`|  |_)  |
        ||   __   |     |  |        |  |     |   ___/
        ||  |  |  |     |  |        |  |     |  |
        ||__|  |__|     |__|        |__|     | _|
        |
        |
      """.stripMargin)



    val StandardTimeout = sys.env.get("HTTP_TIMEOUT").map(_.toLong milliseconds).getOrElse(150 milliseconds)
    val standardTimeout = akka.util.Timeout(StandardTimeout)

    val QueryTimeout = sys.env.get("QUERY_HTTP_TIMEOUT").map(_.toLong milliseconds).getOrElse(350 milliseconds)
    val queryTimeout = akka.util.Timeout(QueryTimeout)


    logging.info("HTTP_TIMEOUT is {}", StandardTimeout._1)
    logging.info("QUERY_HTTP_TIMEOUT is {}", QueryTimeout._1)

    val proxyToAccounts: ActorRef = AccountActor.proxyToShardRegion(system)
    val proxyToCoordinators: ActorRef = Coordinator.proxyToShardRegion(system)
    val accounts = proxyToAccounts
    val coordinators = proxyToCoordinators

    implicit val materializer: ActorMaterializer = ActorMaterializer()

    import system.dispatcher

    val route = path("query" / Segment) {
      accountId => {
        get {
          complete {
            proxyToAccounts.ask(GetBalance(accountId))(queryTimeout, ActorRef.noSender).mapTo[Balance].map((r: Balance) => r.amount.toString)
          }
        }
      }
    } ~ path("deposit" / Segment / IntNumber) {
      case (accountId, amount) =>
        post {
          complete {
            accounts ! ChangeBalance(accountId, amount)
            StatusCodes.Accepted
          }
        }
    } ~ path("withdraw" / Segment / IntNumber) {
      case (accountId, amount) =>
        post {
          complete {
            accounts ! ChangeBalance(accountId, -amount)
            StatusCodes.Accepted
          }
        }
    } ~ path("transaction" / Segment / Segment / Segment / IntNumber) {
      case (transactionId, sourceAccountId, destinationAccountId, amount) => {
        post {
          complete {
            coordinators ! MoneyTransaction(transactionId, sourceAccountId, destinationAccountId, amount, false)
            StatusCodes.Accepted
          }
        }
      }
    } ~ path("status" / Segment) {
      case transactionId => {
        get {
          complete {
            proxyToCoordinators.ask(GetTransactionStatus(transactionId))(queryTimeout, ActorRef.noSender).mapTo[TransactionStatus].map((t) => t.status.toString)
          }
        }
      }
    }

    val bindingFuture = Http().bindAndHandle(route, scala.sys.env.getOrElse("POD_IP", "0.0.0.0"), 8080)

  }


}

