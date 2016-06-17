package proton.game.hermes

import java.time.LocalDateTime
import java.util.UUID

import proton.game.hermes.HermesGameRegion.HermesGameRegion

case class HermesGameFileEntry(time: LocalDateTime, values: Map[HermesGameRegion, Int])

trait HermesGameFile {
  def id: UUID
  def settings: Option[HermesGameFileSettings]
  def getEntry(tick: Int): Option[HermesGameFileEntry]
  def totalTicks: Int
  def complete: Boolean
}
trait HermesGameFileRepository {
  def getById(id: UUID): Option[HermesGameFile]
}
