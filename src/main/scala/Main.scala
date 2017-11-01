package app

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.event.Logging
import app.messages.{Accepted, MoneyTransaction, Rejected}
import fs2.Task
import org.http4s.headers.Connection

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.Try

class Main

object Main extends App {

  implicit val system = ActorSystem("bank")

  Cluster(system).registerOnMemberRemoved {
    // exit JVM when ActorSystem has been terminated
    system.registerOnTermination(System.exit(0))
    // shut down ActorSystem
    system.terminate()

    // In case ActorSystem shutdown takes longer than 10 seconds,
    // exit the JVM forcefully anyway.
    // We must spawn a separate thread to not block current thread,
    // since that would have blocked the shutdown of the ActorSystem.
    new Thread {
      override def run(): Unit = {
        if (Try(Await.ready(system.whenTerminated, 10.seconds)).isFailure)
          System.exit(-1)
      }
    }.start()
  }

  val logging = Logging(system, classOf[Main])

  if (Cluster(system).selfRoles.contains("account")) {
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

  if (Cluster(system).selfRoles.contains("coordinator")) {
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

  if (Cluster(system).selfRoles.contains("http")) {
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

    val balanceQueryTimeoutValue = sys.env.get("BALANCE_QUERY_TIMEOUT").map(_.toLong milliseconds).getOrElse(500 milliseconds)
    val transactionTimeoutValue = sys.env.get("TRANSACTION_TIMEOUT").map(_.toLong milliseconds).getOrElse(1 second)

    object Timeouts {
      implicit val BalanceQueryTimeout = akka.util.Timeout(balanceQueryTimeoutValue)
      implicit val TransactionTimeout = akka.util.Timeout(transactionTimeoutValue)
    }

    import system.dispatcher

    val proxyToAccounts: ActorRef = AccountActor.proxyToShardRegion(system)
    val proxyToCoordinators: ActorRef = Coordinator.proxyToShardRegion(system)

    import akka.pattern.ask
    import org.http4s._
    import org.http4s.dsl._
    import org.http4s.server.blaze.BlazeBuilder

    def askCoordinator(mt: MoneyTransaction): Future[Boolean] = {
      import Timeouts.TransactionTimeout
      (proxyToCoordinators ? MoneyTransaction(mt.transactionId, mt.sourceAccountId, mt.destinationAccountId, mt.amount, replyToSender = true)).map {
        case app.messages.Accepted(_) => true
        case app.messages.Rejected(_) => false
      }
    }

    implicit  val S = fs2.Strategy.fromExecutionContext(system.dispatcher)

    val service = HttpService {
      case GET -> Root / "ping" =>
        Ok("pong")
      case POST -> Root / "transaction" / transactionId / sourceAccountId / destinationAccountId / IntVar(amount) =>
        Task.fromFuture(askCoordinator(MoneyTransaction(transactionId, sourceAccountId, destinationAccountId, amount))).flatMap {
          case true => Ok()
          case false => Conflict()
        }.handleWith {
          case e: Throwable =>
            logging.error(e, "transaction failed")
            InternalServerError()
        }
    }

    BlazeBuilder.bindHttp(8080, sys.env("POD_IP"))
        .mountService(service, "/")
        .run
        .awaitShutdown()

  }


}

