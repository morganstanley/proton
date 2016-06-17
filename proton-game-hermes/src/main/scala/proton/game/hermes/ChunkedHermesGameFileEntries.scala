package proton.game.hermes

import java.util.UUID

import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.persistence.PersistentActor

import scala.collection.mutable.ListBuffer

object ChunkedHermesGameFileEntries {
  trait EntriesMessage
  case class AppendEntries(entries: Seq[HermesGameFileEntry]) extends EntriesMessage
  case class GetEntries() extends EntriesMessage

  trait EntriesEvent
  case class EntriesAppended(entries: Seq[HermesGameFileEntry]) extends EntriesEvent

  trait EntriesResult
  case class EntriesAppendedResult(id: UUID, count: Int) extends EntriesResult
  case class GetEntriesResult(id: UUID, entries: Seq[HermesGameFileEntry]) extends EntriesResult

  val gameFileEntriesRegionName = "hermesGameFileEntries"
}

class ChunkedHermesGameFileEntries(moduleSettings: HermesGameTickerModuleSettings) extends PersistentActor with ActorLogging {
  import context._
  import ChunkedHermesGameFileEntries._

  private val _id: UUID = UUID.fromString(self.path.name)
  private val _entries = new ListBuffer[HermesGameFileEntry]()

  setReceiveTimeout(moduleSettings.chunkedTimeout)

  override def receiveRecover: Receive = {
    case event: EntriesEvent => updateState(event)
  }

  def updateState(e: EntriesEvent) = e match {
    case EntriesAppended(entries) => _entries ++= entries
  }

  override def receiveCommand: Receive = LoggingReceive {
    case AppendEntries(entries) =>
      if (entries.nonEmpty) {
        persist(EntriesAppended(entries))(e => {
          updateState(e)
          sender ! EntriesAppendedResult(_id, entries.size)
        })
      } else {
        sender ! EntriesAppendedResult(_id, 0)
      }
    case GetEntries() => sender ! GetEntriesResult(_id, _entries)
  }

  override def persistenceId: String = "hermes-game-file-entries-" + _id.toString
}