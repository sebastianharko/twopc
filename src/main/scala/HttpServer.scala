package app

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{complete, get, path, post, _}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.duration._
import scala.language.postfixOps


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

  implicit val timeout = akka.util.Timeout(4 seconds)

  val route = path("query" / Segment) {
    accountId => {
      get {
        complete {
          (accounts ? GetBalance(accountId)).mapTo[Int].map(r => Map("amount" -> r))
        }
      }
    }
  } ~ path("deposit" /  Segment / IntNumber) {
      case (accountId, amount) =>
        post {
          complete {
            (accounts ? ChangeBalance(accountId, amount)).map {
              case Accepted(_) => Map("completed" -> true)
              case Rejected(_) => Map("rejected" -> true)
              case AccountStashOverflow(_) => Map("rejected" -> true)
            }
          }
          }
  } ~ path("withdraw" / Segment / IntNumber) {
     case (accountId, amount) =>
       post {
         complete {
           (accounts ? ChangeBalance(accountId, - amount)).map {
             case Accepted(_) => Map("completed" -> true)
             case Rejected(_) => Map("rejected" -> true)
           } }
         }
  }

  val bindingFuture = Http().bindAndHandle(route, scala.sys.env.getOrElse("POD_IP", "0.0.0.0"), 8080)


}

