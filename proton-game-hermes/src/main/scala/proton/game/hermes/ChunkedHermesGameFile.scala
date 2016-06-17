package proton.game.hermes

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props, ReceiveTimeout, Status}
import akka.event.LoggingReceive
import akka.pattern.ask
import akka.util.Timeout
import proton.game.hermes.ChunkedHermesGameFile.ChunkedHermesGameFileDetails
import proton.game.{Envelope, Validation, ValidationException}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object ChunkedHermesGameFileMediator {
  case class GetChunkEntries(id: UUID)
}

class ChunkedHermesGameFileMediator(entries: ActorRef, moduleSettings: HermesGameTickerModuleSettings) extends Actor {
  import context._
  import ChunkedHermesGameFileMediator._
  import ChunkedHermesGameFileEntries._

  var initialSender = self
  setReceiveTimeout(moduleSettings.chunkedRepositoryTimeout)

  override def receive: Receive = LoggingReceive {
    case GetChunkEntries(id) =>
      initialSender = sender
      entries ! Envelope(id, GetEntries())
    case GetEntriesResult(id, entriesSeq) =>
      initialSender ! Status.Success((id, entriesSeq))
      context stop self
    case msg@Validation(message, errorCode, e) =>
      initialSender ! Status.Failure(ValidationException(msg))
      context stop self
    case ReceiveTimeout => context stop self
  }
}

object ChunkedHermesGameFile {

  case class ChunkedHermesGameFileDetails(id: UUID, settings: Option[HermesGameFileSettings], totalTicks: Int,
                                          startTime: Option[LocalDateTime], endTime: Option[LocalDateTime],
                                          entries: Seq[UUID], complete: Boolean, chunkSize: Int)

}

class ChunkedHermesGameFile(entries: ActorRef, details: ChunkedHermesGameFileDetails, system: ActorSystem,
                            moduleSettings: HermesGameTickerModuleSettings)(implicit ec: ExecutionContext)
  extends HermesGameFile {
  import ChunkedHermesGameFileMediator._

  private var _currentChunk: Option[(UUID, Seq[HermesGameFileEntry])] = None
  private var _nextChunk: Option[Future[(UUID, Seq[HermesGameFileEntry])]] = None

  implicit val timeout = Timeout(moduleSettings.chunkedRepositoryTimeout)

  val _mediatorProps = Props(classOf[ChunkedHermesGameFileMediator], entries, moduleSettings)

  override def id: UUID = details.id

  override def totalTicks: Int = details.totalTicks

  override def complete: Boolean = details.complete

  override def settings: Option[HermesGameFileSettings] = details.settings

  override def getEntry(tick: Int): Option[HermesGameFileEntry] = {
    if (tick >= 0 && tick < totalTicks) {
      val chunk = tick / details.chunkSize
      val index = tick % details.chunkSize
      val id = details.entries(chunk)

      _currentChunk.filter(x => x._1 == id) match {
        case Some(tuple) =>
          Some(tuple._2(index))
        case None =>
          _nextChunk match {
            case Some(future) =>
              Await.ready(future, timeout.duration).value match {
                case Some(result) => result match {
                  case Success(e) =>
                    if (e._1 == id) {
                      _currentChunk = Some(e)
                      _nextChunk = None

                      val nextChunk = chunk + 1
                      if (details.entries.size < nextChunk) {
                        val mediator = system.actorOf(_mediatorProps)
                        _nextChunk = Some((mediator ? GetChunkEntries(details.entries(nextChunk)))
                          .mapTo[(UUID, Seq[HermesGameFileEntry])])
                      }

                      Some(e._2(index))
                    } else {
                      fetch(chunk, index, id)
                    }
                  case Failure(ex) => fetch(chunk, index, id)
                }
                case None => fetch(chunk, index, id)
              }
            case None => fetch(chunk, index, id)
          }
      }
    } else {
      None
    }
  }

  private def fetch(chunk: Int, index: Int, id: UUID): Option[HermesGameFileEntry] = {
    _currentChunk = None
    _nextChunk = None

    val mediator = system.actorOf(_mediatorProps)
    val future = (mediator ? GetChunkEntries(id)).mapTo[(UUID, Seq[HermesGameFileEntry])]

    Await.ready(future, timeout.duration).value match {
      case Some(result) => result match {
        case Success(e) =>
          _currentChunk = Some((e._1, e._2))

          val nextChunk = chunk + 1
          if (details.entries.size < nextChunk) {
            val nextMediator = system.actorOf(_mediatorProps)
            _nextChunk = Some((nextMediator ? GetChunkEntries(details.entries(nextChunk)))
              .mapTo[(UUID, Seq[HermesGameFileEntry])])
          }

          Some(e._2(index))
        case Failure(ex) => None
      }
      case None => None
    }
  }
}