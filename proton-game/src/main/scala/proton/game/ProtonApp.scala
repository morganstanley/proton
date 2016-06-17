package proton.game

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

class ProtonModule(config: Config) extends Module {
  bind[ExecutionContext] to scala.concurrent.ExecutionContext.Implicits.global
  bind[ActorSystem] to
    ActorSystem("ProtonGame" + config.getString("proton.game.name"), config) destroyWith (_.terminate())

  bind[JsonPrinter] to CompactPrinter

  bind[GameEventBus] to new GameEventBus
  bind[GameIdGenerator] to new NameHashedGameIdGenerator(config.getString("proton.game.name"))
  bind[GameTickerModuleSettings] identifiedBy required('base) to new GameTickerModuleSettings(
    config.getString("proton.ip"),
    config.getInt("proton.cluster.shards"),
    config.getDuration("proton.http.timeout"),
    inject[GameIdGenerator],
    inject[JsonPrinter])
}

case class ProtonAppMeta(name: String, module: String)

class ProtonApp extends App with Injectable with LazyLogging with SprayJsonSupport with GameProtocol {

  protected def init(file: String, module: Config => Module) = {
    ProtonConfig.parse(file, args).foreach(c => {
      val config = c.config
      implicit val injector = TypesafeConfigInjector(config) :: new ProtonModule(config) :: module(config)
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
          complete(InternalServerError -> Message(message, GameEvents.Unhandled))
      }

      def route(name: String, gameModule: GameModule): Route =
        pathSingleSlash {
          get {
            complete(ProtonAppMeta(name, gameModule.name))
          }
        } ~ gameModule.route

      val gameModule = inject[GameModule]
      gameModule.init()

      Http().bindAndHandle(RouteResult.route2HandlerFlow(route(config.getString("proton.game.name"), gameModule)),
        config.getString("proton.ip"), config.getInt("proton.game.admin.http.port"))
    })
  }
}