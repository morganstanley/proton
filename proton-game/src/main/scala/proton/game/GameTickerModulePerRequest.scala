package proton.game

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, ReceiveTimeout, SupervisorStrategy}
import akka.event.LoggingReceive
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling.{ToResponseMarshallable, ToResponseMarshaller}
import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import akka.http.scaladsl.server.RouteResult
import spray.json.{JsonPrinter, RootJsonFormat}

import scala.concurrent.Promise

class GameTickerModulePerRequest[TConfig <: GameTickerConfig, TSharedState <: GameSharedState,
TPublicState <: GamePublicState : RootJsonFormat, TPrivateState <: GamePrivateState[TPublicState],
TCommand <: GameCommand : RootJsonFormat]
(message: GameMessage[TConfig, TSharedState, TPublicState, TPrivateState, TCommand],
settings: GameTickerModuleSettings,
request: HttpRequest, promise: Promise[RouteResult], target: ActorRef,
envelope: Envelope)
  extends Actor with ActorLogging with SprayJsonSupport with GameProtocol {

  import context._
  import StatusCodes._
  import message._
  import message.GameMessageProtocol._

  implicit val printer: JsonPrinter = settings.jsonPrinter

  setReceiveTimeout(settings.httpTimeout)
  target ! envelope

  def receive = LoggingReceive {
    case res@StatusResult(id, gameMeta) =>
      if (gameMeta.status == GameStatus.GameNotConfigured) {
        complete(NotFound, res)
      }
      else
        complete(OK, res)
    case res: GameResponse => complete(OK, res)
    case v: Validation    => complete(BadRequest, v)
    case ReceiveTimeout   => complete(GatewayTimeout, Validation("Request timeout", GameEvents.Timeout))
  }

  def complete[T <: AnyRef](status: StatusCode, obj: T)(implicit marshaller: ToResponseMarshaller[T]) = {
    ToResponseMarshallable(obj).apply(request) onComplete {
      case scala.util.Success(response) =>
        promise.success(RouteResult.Complete(response.copy(status)))
        stop(self)
      case scala.util.Failure(e) =>
        promise.failure(e)
        stop(self)
    }
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e =>
        complete(InternalServerError, Validation("Could not complete request.", GameEvents.Unhandled, Some(e)))
        SupervisorStrategy.Stop
    }
}
