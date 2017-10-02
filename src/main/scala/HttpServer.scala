package app

import java.util.UUID.randomUUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{complete, get, path, post, _}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.duration._


object Main extends App {

  implicit val system = ActorSystem("minimal")

  ClusterHttpManagement(Cluster(system)).start()

  val accounts = Sharding.accounts(system)

  implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  implicit val executionContext = system.dispatcher

  import de.heikoseeberger.akkahttpjson4s.Json4sSupport._

  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats

  implicit val timeout = akka.util.Timeout(3 seconds)

  val route =
    path("withdraw" / Segment / IntNumber) {
      case (accountId, amount) => {
        post {
          complete {
            (accounts ? Withdraw(accountId, amount)).map {
              case Accepted => Map("result" -> "success")
              case Rejected => Map("result" -> "failure")
            }
          }
        }
      }
    } ~
      path("deposit" / Segment / IntNumber) {
        case (accountId, amount) => {
          post {
            complete {
              (accounts ? Deposit(accountId, amount)).map {
                case Accepted => Map("result" -> "success")
                case Rejected => Map("result" -> "failure")
              }
            }
          }
        }
      } ~ path("query" / Segment) {
      case accountId => {
        get {
          complete {
            (accounts ? Query(accountId)).mapTo[Int].map {
              case r => Map("amount" -> r)
            }
          }
        }
      }
    } ~ path("transaction" / Segment / Segment / IntNumber) {
      case (sourceId, destId, amount) => {
        post {
          complete {
            val id = randomUUID().toString
            val t: ActorRef = ???
            (t ? ???).map {
              case Accepted => Map("result" -> "success")
              case Rejected => Map("result" -> "failure")
            }
          }
        }
      }
    }

  val bindingFuture = Http().bindAndHandle(route, scala.sys.env.getOrElse("POD_IP", "0.0.0.0"), 8080)


}

