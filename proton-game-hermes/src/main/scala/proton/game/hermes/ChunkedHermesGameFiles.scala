package proton.game.hermes

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.{ActorLogging, ActorRef, Cancellable}
import akka.event.LoggingReceive
import akka.persistence.PersistentActor
import proton.game.hermes.ChunkedHermesGameFile.ChunkedHermesGameFileDetails
import proton.game.Validation

import scala.collection.mutable.ListBuffer

object ChunkedHermesGameFiles {
  trait GameFileMessage
  case class CreateGameFile() extends GameFileMessage
  case class ChangeGameFileSettings(settings: HermesGameFileSettings) extends GameFileMessage
  case class InitialGameFileEntries(entries: UUID,
                                    count: Int,
                                    startTime: LocalDateTime,
                                    endTime: LocalDateTime) extends GameFileMessage
  case class AppendGameFileEntries(previousEntries: UUID,
                                   entries: UUID,
                                   count: Int,
                                   endTime: LocalDateTime) extends GameFileMessage
  case class ReplaceGameFileEntries(previousEntries: UUID,
                                    entries: UUID,
                                    delta: Int,
                                    endTime: LocalDateTime) extends GameFileMessage
  case class MarkGameFileComplete() extends GameFileMessage
  case class GetGameFileDetails() extends GameFileMessage

  trait GameFileEvent
  case class GameFileCreated(settings: HermesGameFileSettings, chunkSize: Int) extends GameFileEvent
  case class ChangedGameFileSettings(settings: HermesGameFileSettings) extends GameFileEvent
  case class GameFileEntriesChanged(id: UUID, count: Int, replaced: Boolean, startTime: Option[LocalDateTime],
                                    endTime: LocalDateTime) extends GameFileEvent
  case class GameFileCompleted() extends GameFileEvent

  trait GameFileResult
  case class GameFileCreatedResult(details: ChunkedHermesGameFileDetails, created: Boolean) extends GameFileResult
  case class GameFileChangeSettingsResult(details: ChunkedHermesGameFileDetails) extends GameFileResult
  case class GameFileEntriesChangedResult(details: ChunkedHermesGameFileDetails) extends GameFileResult
  case class GameFileCompletedResult(details: ChunkedHermesGameFileDetails, marked: Boolean) extends GameFileResult
  case class GetGameFileDetailsResult(details: ChunkedHermesGameFileDetails) extends GameFileResult

  val gameFilesRegionName = "hermesGameFiles"
}

class ChunkedHermesGameFiles(moduleSettings: HermesGameTickerModuleSettings)
  extends PersistentActor with ActorLogging {
  import context._
  import ChunkedHermesGameFiles._

  private val _id: UUID = UUID.fromString(self.path.name)
  private var _settings: Option[HermesGameFileSettings] = None
  private var _complete = false
  private var _chunkSize = 0
  private var _totalTicks = 0
  private var _startTime: Option[LocalDateTime] = None
  private var _endTime: Option[LocalDateTime] = None
  private val _entries = new ListBuffer[UUID]()
  private var _appendingEntries: Option[(ActorRef, Seq[HermesGameFileEntry], Int, Cancellable)] = None

  setReceiveTimeout(moduleSettings.chunkedTimeout)

  def updateState(e: GameFileEvent) = e match {
    case GameFileCreated(settings, chunkSize) =>
      _settings = Some(settings)
      _chunkSize = chunkSize
    case ChangedGameFileSettings(settings) => _settings = Some(settings)
    case GameFileEntriesChanged(id, count, replace, startTime, endTime) =>
      _totalTicks += count

      if (_startTime.isEmpty) {
        startTime.foreach(t => _startTime = Some(t))
      }

      _endTime = Some(endTime)

      if (replace && _entries.nonEmpty) {
        _entries.trimEnd(1)
      }

      _entries += id
    case GameFileCompleted() => _complete = true
  }

  override def receiveRecover: Receive = {
    case event: GameFileEvent => updateState(event)
  }

  override def receiveCommand: Receive = LoggingReceive {
    case CreateGameFile() =>
      _settings match {
        case Some(s) =>
          sender ! GameFileCreatedResult(getDetails, created = false)
        case None =>
          persist(GameFileCreated(new HermesGameFileSettings(), moduleSettings.chunkSize))(e => {
            updateState(e)
            sender ! GameFileCreatedResult(getDetails, created = true)
          })
      }
    case ChangeGameFileSettings(settings) =>
      if (_complete) {
        sender ! Validation(s"Game file ${_id} was marked as complete.", HermesGameEvents.MarkedComplete)
      } else {
        _settings match {
          case Some(s) =>
            persist(ChangedGameFileSettings(settings))(e => {
              updateState(e)
              sender ! GameFileChangeSettingsResult(getDetails)
            })
          case None => sender ! Validation(s"Game file ${_id} has not been created properly.",
            HermesGameEvents.CreationIncomplete)
        }
      }
    case InitialGameFileEntries(entries, count, startTime, endTime) =>
      if (_complete) {
        sender ! Validation(s"Game file ${_id} was marked as complete.", HermesGameEvents.MarkedComplete)
      } else if (count <= 0) {
        sender ! Validation("There must be entries to add.", HermesGameEvents.NoEntries)
      } else {
        persist(GameFileEntriesChanged(entries, count, replaced = false, Some(startTime), endTime))(e => {
          updateState(e)
          sender ! GameFileEntriesChangedResult(getDetails)
        })
      }
    case AppendGameFileEntries(previousEntries, entries, count, endTime) =>
      if (_complete) {
        sender ! Validation(s"Game file ${_id} was marked as complete.", HermesGameEvents.MarkedComplete)
      } else if (count <= 0) {
        sender ! Validation("There must be entries to add.", HermesGameEvents.NoEntries)
      } else {
        if (_entries.lastOption.contains(previousEntries)) {
          persist(GameFileEntriesChanged(entries, count, replaced = false, None, endTime))(e => {
            updateState(e)
            sender ! GameFileEntriesChangedResult(getDetails)
          })
        } else {
          sender ! Validation(s"The previous entries $previousEntries in game file ${_id} was not found.",
            HermesGameEvents.PreviousEntriesNotFound)
        }
      }
    case ReplaceGameFileEntries(previousEntries, entries, count, endTime) =>
      if (_complete) {
        sender ! Validation(s"Game file ${_id} was marked as complete.", HermesGameEvents.MarkedComplete)
      } else if (count <= 0) {
        sender ! Validation("There must be entries to add.", HermesGameEvents.NoEntries)
      } else {
        if (_entries.lastOption.contains(previousEntries)) {
          persist(GameFileEntriesChanged(entries, count, replaced = true, None, endTime))(e => {
            updateState(e)
            sender ! GameFileEntriesChangedResult(getDetails)
          })
        } else {
          sender ! Validation(s"The previous entries $previousEntries in game file ${_id} was not found.",
            HermesGameEvents.PreviousEntriesNotFound)
        }
      }
    case MarkGameFileComplete() =>
      if (_complete) {
        sender ! GameFileCompletedResult(getDetails, marked = false)
      } else {
        if (_entries.nonEmpty && _settings.nonEmpty) {
          persist(GameFileCompleted())(e => {
            updateState(e)
            sender ! GameFileCompletedResult(getDetails, marked = true)
          })
        } else {
          sender ! Validation(s"Game file ${_id} needs to have valid settings and entries.",
            HermesGameEvents.NotConfiguredCorrectly)
        }
      }
    case GetGameFileDetails() => sender ! GetGameFileDetailsResult(getDetails)
  }

  private def getDetails =
    new ChunkedHermesGameFileDetails(_id, _settings, _totalTicks, _startTime, _endTime, _entries, _complete, _chunkSize)

  override def persistenceId: String = "hermes-game-file-" + _id.toString
}