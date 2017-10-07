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
          (accounts ? Query(accountId)).mapTo[Int].map { case r => Map("amount" -> r) }
        }
      }
    }
  } ~ path("transaction" / Segment / Segment / IntNumber) {
      case (sourceAccountId, destinationAccountId, amount) => {
        post {
          complete {
            Map("result" -> "success")
          }
        }
      }
    }

  val bindingFuture = Http().bindAndHandle(route, scala.sys.env.getOrElse("POD_IP", "0.0.0.0"), 8080)


}

