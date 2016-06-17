package proton.game.hermes

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout, Status}
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import proton.game.hermes.ChunkedHermesGameFile.ChunkedHermesGameFileDetails
import proton.game.hermes.ChunkedHermesGameFileRepositoryMediator.GetGameFileById
import proton.game.{Envelope, Validation, ValidationException}

import scala.concurrent.{Await, ExecutionContext}

object ChunkedHermesGameFileRepositoryMediator {
  case class GetGameFileById(id: UUID)
}

class ChunkedHermesGameFileRepositoryMediator(gameFiles: ActorRef, settings: HermesGameTickerModuleSettings) extends Actor {
  import context._
  import ChunkedHermesGameFiles._
  import ChunkedHermesGameFileRepositoryMediator._

  var initialSender = self

  setReceiveTimeout(settings.chunkedRepositoryTimeout)

  override def receive: Receive = LoggingReceive {
    case GetGameFileById(id) =>
      initialSender = sender
      gameFiles ! Envelope(id, GetGameFileDetails())
    case GetGameFileDetailsResult(details) =>
      initialSender ! Status.Success(details)
      context stop self
    case msg@Validation(message, errorCode, e) =>
      initialSender ! Status.Failure(ValidationException(msg))
      context stop self
    case ReceiveTimeout => context stop self
  }
}

class ChunkedHermesGameFileRepository(settings: HermesGameTickerModuleSettings, system: ActorSystem)
                                     (implicit ec: ExecutionContext)
  extends HermesGameFileRepository {

  lazy val gameFiles: ActorRef =
    ClusterSharding(system).shardRegion(ChunkedHermesGameFiles.gameFilesRegionName)
  lazy val gameFileEntries: ActorRef =
    ClusterSharding(system).shardRegion(ChunkedHermesGameFileEntries.gameFileEntriesRegionName)

  implicit val timeout = Timeout(settings.chunkedRepositoryTimeout)

  override def getById(id: UUID): Option[HermesGameFile] = {
    val mediator = system.actorOf(Props(classOf[ChunkedHermesGameFileRepositoryMediator], gameFiles, settings))

    val future = (mediator ? GetGameFileById(id)).mapTo[ChunkedHermesGameFileDetails]

    Await.ready(future, settings.chunkedRepositoryTimeout).value.flatMap(t => t.toOption.map(d =>
      new ChunkedHermesGameFile(gameFileEntries, d, system, settings)))
  }
}
