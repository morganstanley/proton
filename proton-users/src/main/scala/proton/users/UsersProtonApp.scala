package proton.users

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route, RouteResult}
import akka.stream.ActorMaterializer
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import scaldi.{Injectable, Module, TypesafeConfigInjector}
import spray.json.{CompactPrinter, JsonPrinter}

import scala.concurrent.ExecutionContext

class UsersProtonModule(config: Config) extends Module {
  bind[ExecutionContext] to scala.concurrent.ExecutionContext.Implicits.global
  bind[ActorSystem] to ActorSystem("ProtonUsers", config) destroyWith (_.terminate())
  bind[JsonPrinter] to CompactPrinter
}

object UsersProtonApp extends App with Injectable with LazyLogging with SprayJsonSupport with UsersProtocol {
  ProtonConfig.parse("users-dev.conf", args).foreach(c => {
    val config = c.config
    implicit val injector = TypesafeConfigInjector(config) :: new UsersProtonModule(config)
    implicit val executionContext = inject[ExecutionContext]
    implicit val system = inject[ActorSystem]
    implicit val materializer = ActorMaterializer()
    implicit val printer = inject[JsonPrinter]

    implicit val exceptionHandler = ExceptionHandler {
      case e: Exception =>
        logger.error("HTTP unhandled exception.", e)
        var message = "HTTP unhandled exception."
        if (e != null) {
          message = e.getMessage
        }
        complete(InternalServerError -> Message(message, UsersEvents.Unhandled))
    }

    def route: Route =
      pathSingleSlash {
        get {
          complete("test")
        }
      }

    Http().bindAndHandle(route, config.getString("proton.ip"), config.getInt("proton.users.http.port"))
  })
}
