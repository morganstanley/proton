package proton.game.hermes

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, ReceiveTimeout}
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator._
import akka.event.LoggingReceive
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import akka.http.scaladsl.server.RouteResult
import proton.game.hermes.ChunkedHermesGameFile.ChunkedHermesGameFileDetails
import proton.game.hermes.ChunkedHermesGameFiles.GameFileMessage
import proton.game.hermes.HermesGameTickerModulePerRequest.HermesGameTickerModulePerRequestMessage
import proton.game._
import spray.json.JsonPrinter

import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise
import scala.util.Try

object HermesGameTickerModulePerRequest {
  trait HermesGameTickerModulePerRequestMessage
  case class CreateAdvertisementRequest(name: String, id: Option[UUID] = None, gameFiles: ActorRef,
                                        gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class SearchVisibleFilesRequest(name: Option[String], replicator: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class SearchFilesRequest(name: Option[String], gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class GetFileRequestById(id: UUID, gameFiles: ActorRef, gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class DeleteFileRequest(id: UUID, gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class ChangeFileNameRequest(id: UUID, name: String, gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class ChangeFileSettingsRequest(id: UUID, settings: HermesGameFileSettings, gameFiles: ActorRef,
                                       gameFileAdvertisements: ActorRef) extends HermesGameTickerModulePerRequestMessage
  case class SetFileVisibilityRequest(id: UUID, visible: Boolean, gameFiles: ActorRef, gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class SetFileCompleteRequest(id: UUID, complete: Boolean, gameFiles: ActorRef, gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class AddFileEntriesRequest(id: UUID, entries: Seq[HermesGameFileEntry], gameFiles: ActorRef,
                                   gameFileEntries: ActorRef, gameFileAdvertisements: ActorRef)
    extends HermesGameTickerModulePerRequestMessage
  case class GetFileEntriesRequestById(id: UUID, entriesId: UUID, gameFileEntries: ActorRef, gameFiles: ActorRef,
                                       gameFileAdvertisements: ActorRef) extends HermesGameTickerModulePerRequestMessage

  case class HermesGameFileAdvertisementResult(name: String, id: UUID, time: LocalDateTime,
                                               visible: Option[Boolean] = None)
  case class HermesGameTickerModulePerRequestGetResults(files: Seq[HermesGameFileAdvertisementResult])
  case class HermesGameTickerModulePerRequestGetEntriesResults(entries: Seq[HermesGameFileEntry])
  case class HermesGameTickerModulePerRequestDeleteResult(removed: Boolean)
  case class HermesGameTickerModulePerRequestDetailsResult(id: Option[UUID] = None,
                                                           name: Option[String] = None,
                                                           created: Option[LocalDateTime] = None,
                                                           visible: Option[Boolean] = None,
                                                           settings: Option[HermesGameFileSettings] = None,
                                                           totalTicks: Option[Int] = None,
                                                           startTime: Option[LocalDateTime] = None,
                                                           endTime: Option[LocalDateTime]= None,
                                                           entries: Option[Seq[UUID]] = None,
                                                           complete: Option[Boolean] = None,
                                                           chunkSize: Option[Int] = None)
}

class HermesAddEntriesState {
  var index = 0
  var entries: Seq[HermesGameFileEntry] = Seq()
  var lastMessage: Option[GameFileMessage] = None
  var lastDetails: Option[ChunkedHermesGameFileDetails] = None
  var lastDateTime: Option[LocalDateTime] = None
}

class HermesGameTickerModulePerRequest(settings: HermesGameTickerModuleSettings, request: HttpRequest,
                                       promise: Promise[RouteResult], msg: HermesGameTickerModulePerRequestMessage)
  extends Actor with ActorLogging with SprayJsonSupport with HermesGameProtocol {

  import context._
  import StatusCodes._
  import ChunkedHermesGameFiles._
  import ChunkedHermesGameFileEntries._
  import HermesGameTickerModulePerRequest._
  import SingletonHermesGameFileAdvertisements._

  implicit val printer: JsonPrinter = settings.jsonPrinter

  var _advertisement: Option[HermesGameFileAdvertisement] = None

  setReceiveTimeout(settings.httpTimeout)

  msg match {
    case CreateAdvertisementRequest(name, id, gameFiles, gameFileAdvertisements) =>
      val newId = id.getOrElse(UUID.randomUUID())
      gameFileAdvertisements ! AddAdvertisement(name, newId)
      become(createAdvertisementReceive)
    case SearchFilesRequest(name, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAllAdvertisements()
      become(getAdvertisementsReceive)
    case SearchVisibleFilesRequest(name, replicator) =>
      replicator ! Get(SingletonHermesGameFileAdvertisements.mapKey, ReadLocal)
      become(getVisibleAdvertisementsReceive)
    case GetFileRequestById(id, gameFiles, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAdvertisementById(id)
      become(getFileReceive)
    case DeleteFileRequest(id, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAdvertisementById(id)
      become(deleteFileReceive())
    case ChangeFileNameRequest(id, name, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAdvertisementById(id)
      become(changeFileNameReceive)
    case ChangeFileSettingsRequest(id, gameSettings, gameFiles, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAdvertisementById(id)
      become(changeFileSettingsReceive)
    case SetFileVisibilityRequest(id, visible, gameFiles, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAdvertisementById(id)
      become(setFileVisibilityReceive())
    case SetFileCompleteRequest(id, isComplete, gameFileEntries, gameFileAdvertisements) => isComplete match {
      case true =>
        gameFileAdvertisements ! GetAdvertisementById(id)
        become(setFileCompleteReceive())
      case false =>
        complete(BadRequest,
          Validation(s"Game files cannot be un-marked complete.", HermesGameEvents.GameFileNotComplete))
    }
    case AddFileEntriesRequest(id, entries, gameFiles, gameFileEntries, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAdvertisementById(id)
      become(addFileEntriesReceive())
    case GetFileEntriesRequestById(id, entriesId, gameFiles, gameFileEntries, gameFileAdvertisements) =>
      gameFileAdvertisements ! GetAdvertisementById(id)
      become(getFileEntriesReceive)
  }
  override def receive: Receive = LoggingReceive {
    case v: Validation    => v.errorCode match {
      case HermesGameEvents.AdvertisementDoesNotExist => complete(NotFound, v)
      case _ => complete(BadRequest, v)
    }
    case ReceiveTimeout   => complete(GatewayTimeout, Validation("Request timeout", GameEvents.Timeout))
  }

  def createAdvertisementReceive: Receive = receive orElse {
    case AdvertisementAddedResult(advertisement, added) =>
      _advertisement = Some(advertisement)
      msg match {
        case CreateAdvertisementRequest(name, id, gameFiles, gameFileAdvertisements) =>
          gameFiles ! Envelope(advertisement.id, CreateGameFile())
      }
    case GameFileCreatedResult(details, created) => complete(OK, _advertisement, Some(details))
  }

  def getAdvertisementsReceive: Receive = receive orElse {
    case GetAdvertisementsResult(advertisements) =>
      val sorted = advertisements.sortWith(_.name < _.name)
      var filtered = sorted

      msg match {
        case SearchFilesRequest(name, replicator) =>
          filtered = name.map(n => n.toLowerCase).map(n => sorted.filter(s => s.name.toLowerCase.contains(n)))
            .getOrElse(sorted)
      }

      complete(filtered)
  }

  def getVisibleAdvertisementsReceive: Receive = receive orElse {
    case g @ GetSuccess(SingletonHermesGameFileAdvertisements.mapKey, req) =>
      val sorted = g.get(SingletonHermesGameFileAdvertisements.mapKey).elements.toSeq.sortWith(_.name < _.name)
      var filtered = sorted

      msg match {
        case SearchVisibleFilesRequest(name, replicator) =>
          filtered = name.map(n => n.toLowerCase).map(n => sorted.filter(s => s.name.toLowerCase.contains(n)))
            .getOrElse(sorted)
      }

      complete(filtered)
    case Replicator.NotFound(SingletonHermesGameFileAdvertisements.mapKey, req) =>
      complete(OK, HermesGameTickerModulePerRequestGetResults(Seq()))
    case GetFailure(SingletonHermesGameFileAdvertisements.mapKey, req) =>
      complete(InternalServerError, Validation("Could not complete request.", HermesGameEvents.CouldNotSearchNames,
        Some(new Exception("Failed to read distributed advertisements."))))
  }

  def getFileReceive: Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      _advertisement = Some(advertisement)
      msg match {
        case GetFileRequestById(id, gameFiles, gameFileAdvertisements) =>
          gameFiles ! Envelope(advertisement.id, GetGameFileDetails())
      }
    case GetGameFileDetailsResult(details) => complete(OK, _advertisement, Some(details))
  }

  def deleteFileReceive(): Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      msg match {
        case DeleteFileRequest(id, gameFileAdvertisements) =>
          gameFileAdvertisements ! RemoveAdvertisement(advertisement.name)
      }
    case AdvertisementRemoveResult(removed) => complete(OK, HermesGameTickerModulePerRequestDeleteResult(removed))
  }

  def changeFileNameReceive: Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      msg match {
        case ChangeFileNameRequest(id, name, gameFileAdvertisements) =>
          gameFileAdvertisements ! ChangeAdvertisementName(advertisement.name, name)
      }
    case AdvertisementChangeNameResult(advertisement) => complete(OK,
      new HermesGameFileAdvertisementResult(advertisement.name, advertisement.id, advertisement.created,
        advertisement.visible))
  }

  def changeFileSettingsReceive: Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      msg match {
        case ChangeFileSettingsRequest(id, gameSettings, gameFiles, gameFileAdvertisements) =>
          gameFiles ! Envelope(id, ChangeGameFileSettings(gameSettings))
      }
    case GameFileChangeSettingsResult(details) => complete(OK, _advertisement, Some(details))
  }

  def setFileVisibilityReceive(): Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      _advertisement = Some(advertisement)
      msg match {
        case SetFileVisibilityRequest(id, visible, gameFiles, gameFileAdvertisements) =>
          visible match {
            case true => gameFiles ! Envelope(id, GetGameFileDetails())
            case false => gameFileAdvertisements ! SetAdvertisementVisibility(advertisement.name, visible)
          }
      }
    case GetGameFileDetailsResult(details) =>
      details.complete match {
        case true =>
          msg match {
            case SetFileVisibilityRequest(id, visible, gameFiles, gameFileAdvertisements) =>
              _advertisement.foreach(x => gameFileAdvertisements ! SetAdvertisementVisibility(x.name, visible))
          }
        case false =>
          complete(BadRequest, Validation(s"The game file with id ${details.id} is not marked as complete.",
            HermesGameEvents.GameFileNotComplete))
      }
    case AdvertisementVisibilitySetResult(advertisement, updated) => complete(OK,
      new HermesGameFileAdvertisementResult(advertisement.name, advertisement.id, advertisement.created,
        advertisement.visible))
  }

  def setFileCompleteReceive(): Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      _advertisement = Some(advertisement)
      msg match {
        case SetFileCompleteRequest(id, complete, gameFiles, gameFileAdvertisements) =>
          gameFiles ! Envelope(id, MarkGameFileComplete())
      }
    case GameFileCompletedResult(details, marked) => complete(OK, _advertisement, Some(details))
  }

  lazy val addFileEntriesState = new HermesAddEntriesState()

  def addFileEntriesReceive(): Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      _advertisement = Some(advertisement)
      msg match {
        case AddFileEntriesRequest(id, entries, gameFiles, gameFileEntries, gameFileAdvertisements) =>
          gameFiles ! Envelope(id, GetGameFileDetails())
      }
    case GetGameFileDetailsResult(details) =>
      addFileEntriesState.lastDetails = Some(details)
      msg match {
        case AddFileEntriesRequest(id, entries, gameFiles, gameFileEntries, gameFileAdvertisements) =>
          details.entries.lastOption match {
            case Some(entry) => gameFileEntries ! Envelope(entry, GetEntries())
            case None => DoInitialEntries(id, entries, gameFileEntries)
          }
      }
    case GetEntriesResult(entryId, existingEntries) =>
      msg match {
        case AddFileEntriesRequest(id, entries, gameFiles, gameFileEntries, gameFileAdvertisements) =>
          DoContinuingEntries(id, entries, entryId, existingEntries, gameFileEntries)
      }
    case EntriesAppendedResult(entryId, entriesCount) =>
      msg match {
        case AddFileEntriesRequest(id, entries, gameFiles, gameFileEntries, gameFileAdvertisements) =>
          addFileEntriesState.lastMessage match {
            case Some(message) => gameFiles ! Envelope(id, message)
            case None => complete(InternalServerError,
              Validation(s"Failed to add entries to $id as the next action was missing.",
                HermesGameEvents.AddInitialEntriesFailed, None))
          }
      }
    case GameFileEntriesChangedResult(details) =>
      addFileEntriesState.lastDetails = Some(details)
      msg match {
        case AddFileEntriesRequest(id, entries, gameFiles, gameFileEntries, gameFileAdvertisements) =>
          DoNextEntries(id, details.entries.last, gameFileEntries)
      }
  }

  private def DoInitialEntries(id: UUID, entries: Seq[HermesGameFileEntry], gameFileEntries: ActorRef) = {
    addFileEntriesState.entries = entries
    val entryId = UUID.randomUUID()

    GetEntriesToAdd(addFileEntriesState.index, ListBuffer[HermesGameFileEntry]()) match {
      case util.Success(toAdd) =>
        if (toAdd.nonEmpty) {
          addFileEntriesState.lastMessage = Some(InitialGameFileEntries(entryId, toAdd.size, toAdd.head.time,
            toAdd.last.time))
          gameFileEntries ! Envelope(entryId, AppendEntries(toAdd))
        } else {
          complete(OK, _advertisement, addFileEntriesState.lastDetails)
        }
      case util.Failure(ex) => ex match {
        case ex: ValidationException => complete(InternalServerError, ex.validation)
        case ex: Exception => complete(InternalServerError, Validation(s"Failed to add initial entries to $id.",
          HermesGameEvents.AddInitialEntriesFailed, Some(ex)))
      }
    }
  }

  private def DoContinuingEntries(id: UUID, entries: Seq[HermesGameFileEntry], previous: UUID,
                                  previousEntries: Seq[HermesGameFileEntry], gameFileEntries: ActorRef) = {
    if (previousEntries.size < settings.chunkSize) {
      addFileEntriesState.entries = previousEntries ++ entries
    } else {
      addFileEntriesState.entries = entries
      addFileEntriesState.lastDateTime = previousEntries.lastOption.map(x => x.time)
    }

    val entryId = UUID.randomUUID()

    GetEntriesToAdd(addFileEntriesState.index, ListBuffer[HermesGameFileEntry]()) match {
      case util.Success(toAdd) =>
        if (toAdd.nonEmpty) {
          if (previousEntries.size < settings.chunkSize) {
            addFileEntriesState.lastMessage = Some(ReplaceGameFileEntries(previous, entryId,
              toAdd.size - previousEntries.size, toAdd.last.time))
            gameFileEntries ! Envelope(entryId, AppendEntries(toAdd))
          } else {
            addFileEntriesState.lastMessage = Some(AppendGameFileEntries(previous, entryId,
              toAdd.size, toAdd.last.time))
            gameFileEntries ! Envelope(entryId, AppendEntries(toAdd))
          }
        } else {
          complete(OK, _advertisement, addFileEntriesState.lastDetails)
        }
      case util.Failure(ex) => ex match {
        case ex: ValidationException => complete(InternalServerError, ex.validation)
        case ex: Exception => complete(InternalServerError, Validation(s"Failed to append entries to $id.",
          HermesGameEvents.AddInitialEntriesFailed, Some(ex)))
      }
    }
  }

  private def DoNextEntries(id: UUID, previous: UUID, gameFileEntries: ActorRef) = {
    if (addFileEntriesState.index < addFileEntriesState.entries.size) {
      GetEntriesToAdd(addFileEntriesState.index, ListBuffer[HermesGameFileEntry]()) match {
        case util.Success(toAdd) =>
          if (toAdd.nonEmpty) {
            val entryId = UUID.randomUUID()
            addFileEntriesState.lastMessage = Some(AppendGameFileEntries(previous, entryId, toAdd.size,
              toAdd.last.time))
            gameFileEntries ! Envelope(entryId, AppendEntries(toAdd))
          } else {
            complete(OK, _advertisement, addFileEntriesState.lastDetails)
          }
        case util.Failure(ex) => ex match {
          case ex: ValidationException => complete(InternalServerError, ex.validation)
          case ex: Exception => complete(InternalServerError, Validation(s"Failed to append aditional entries to $id.",
            HermesGameEvents.AddInitialEntriesFailed, Some(ex)))
        }
      }
    } else {
      complete(OK, _advertisement, addFileEntriesState.lastDetails)
    }
  }

  private def GetEntriesToAdd(index: Int, toAdd: ListBuffer[HermesGameFileEntry]): Try[Seq[HermesGameFileEntry]] = {
    if (toAdd.size < settings.chunkSize && index < addFileEntriesState.entries.size) {
      val entry = addFileEntriesState.entries(index)
      if (addFileEntriesState.lastDateTime.getOrElse(LocalDateTime.MIN).isAfter(entry.time)) {
        util.Failure(ValidationException(
          s"The entry with time ${entry.time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} appears out of order and cannot proceed.",
          HermesGameEvents.EntryNotSequential))
      } else {
        toAdd += entry
        addFileEntriesState.lastDateTime = Some(entry.time)
        GetEntriesToAdd(index + 1, toAdd)
      }
    } else {
      addFileEntriesState.index = index + 1
      util.Success(toAdd)
    }
  }

  def getFileEntriesReceive: Receive = receive orElse {
    case GetAdvertisementResult(advertisement) =>
      _advertisement = Some(advertisement)
      msg match {
        case GetFileEntriesRequestById(id, entriesId, gameFiles, gameFileEntries, gameFileAdvertisements) =>
          gameFiles ! Envelope(id, GetGameFileDetails())
      }
    case GetGameFileDetailsResult(details) =>
      msg match {
        case GetFileEntriesRequestById(id, entriesId, gameFiles, gameFileEntries, gameFileAdvertisements) =>
          details.entries.contains(entriesId) match {
            case true => gameFileEntries ! Envelope(entriesId, GetEntries())
            case false => complete(NotFound, HermesGameTickerModulePerRequestGetEntriesResults(Seq()))
          }
      }
    case GetEntriesResult(id, entries) => complete(OK, HermesGameTickerModulePerRequestGetEntriesResults(entries))
  }

  def complete(advertisements: Seq[HermesGameFileAdvertisement]): Unit = {
    complete(OK, HermesGameTickerModulePerRequestGetResults(
      advertisements.map(x => new HermesGameFileAdvertisementResult(x.name, x.id, x.created, x.visible))))
  }

  def complete(status: StatusCode, advertisement: Option[HermesGameFileAdvertisement] = None,
              details: Option[ChunkedHermesGameFileDetails] = None) : Unit = {
    complete(status, HermesGameTickerModulePerRequestDetailsResult(details.map(x => x.id),
      advertisement.map(x => x.name), advertisement.map(x => x.created), advertisement.flatMap(x => x.visible),
      details.flatMap(x => x.settings), details.map(x => x.totalTicks), details.flatMap(x => x.startTime),
      details.flatMap(x => x.endTime), details.map(x => x.entries), details.map(x => x.complete),
      details.map(x => x.chunkSize)))
  }

  def complete[T <: AnyRef](status: StatusCode, obj: T)(implicit marshaller: ToResponseMarshaller[T]): Unit = {
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
        Stop
    }
}
