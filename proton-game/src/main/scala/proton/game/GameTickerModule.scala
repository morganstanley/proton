package proton.game

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Route, RouteResult}
import akka.http.scaladsl.server.Directives._
import spray.json._

import scala.concurrent.Promise
import scala.reflect.ClassTag

object GameTickerModule {
  case class GameTickerMessageName(name: String)
}

abstract class GameTickerModule[TConfig <: GameTickerConfig, TSharedState <: GameSharedState,
TPublicState <: GamePublicState : RootJsonFormat, TPrivateState <: GamePrivateState[TPublicState],
TCommand <: GameCommand : RootJsonFormat,
TTicker <: GameTicker[TConfig, TSharedState, TPublicState, TPrivateState, TCommand] : ClassTag,
TConfigFactory <: GameTickerConfigFactory[TConfig] : RootJsonFormat]
(val system: ActorSystem, message: GameMessage[TConfig, TSharedState, TPublicState, TPrivateState, TCommand],
 settings: GameTickerModuleSettings, eventBus: GameEventBus)
  extends GameModule with SprayJsonSupport with GameProtocol {

  import message._
  import StatusCodes._
  import GameTickerModule._

  implicit val printer: JsonPrinter = settings.jsonPrinter

  lazy val endpointInstance = getEndpoint
  lazy val gameTickerRegion: ActorRef = ClusterSharding(system).start(
    typeName = name,
    entityProps = tickerProps,
    settings = ClusterShardingSettings(system),
    extractEntityId = EnvelopeEntity.extractEntityId,
    extractShardId = EnvelopeEntity.extractShardId(settings.numberOfShards))

  protected def additionalTickerPropArgs: Seq[Any] = Seq()

  protected def tickerProps = Props(implicitly[ClassTag[TTicker]].runtimeClass,
    Seq(endpointInstance, eventBus) ++ additionalTickerPropArgs:_*)

  def getEndpoint: GameEndPoint

  def decorateRoute(route: Route): Route = route

  override def route: Route = decorateRoute {
    path(name / "game") {
        post {
          entity(as[TConfigFactory]) { factory =>
            val config = factory.build()
            val id = settings.idGenerator.generate(name)

            preRequest(gameTickerRegion, id, Configure(config))
          }
        }
    } ~ path(name / "game" / JavaUUID) { id =>
      get {
        preRequest(gameTickerRegion, id, GetStatus())
      } ~ post {
        entity(as[GameTickerMessageName]) { wrapper =>
          wrapper.name match {
            case s if s matches "(?i)Start" => preRequest(gameTickerRegion, id, Start())
            case s if s matches "(?i)Stop" => preRequest(gameTickerRegion, id, Stop())
            case s if s matches "(?i)Clear" => preRequest(gameTickerRegion, id, Clear())
            case _ => complete(BadRequest -> Validation(
              s"The game command ${wrapper.name} is invalid. Please choose from Start, Stop or Clear.",
              GameEvents.ControlCommandInvalid))
          }
        }
      }
    } ~ path(name / "game" / JavaUUID / "state") { id =>
      get {
        preRequest(gameTickerRegion, id, GetState())
      }
    } ~ path(name / "game" / JavaUUID / "player" / JavaUUID) { (gameId, playerId) =>
      preRequest(gameTickerRegion, gameId, GetPlayer(playerId))
    }
  }

  def preRequest(target: ActorRef, id: UUID, payload: GameRequest): Route =
    httpRequest => {
      val p = Promise[RouteResult]()

      system.actorOf(Props(
        classOf[GameTickerModulePerRequest[TConfig, TSharedState, TPublicState, TPrivateState, TCommand]], message,
        settings, httpRequest.request, p, target, Envelope(id, payload), implicitly[RootJsonFormat[TPublicState]],
        implicitly[RootJsonFormat[TCommand]]))

      p.future
    }

  override def init() = {
    gameTickerRegion
    endpointInstance.init()
  }
}