package proton.game

import java.util.UUID

import akka.cluster.sharding.ShardRegion

final case class Envelope(id: UUID, message: Any)

object EnvelopeEntity {
  def extractEntityId: ShardRegion.ExtractEntityId = {
    case Envelope(id, payload) ⇒ (id.toString, payload)
  }

  def extractShardId(numberOfShards: Int): ShardRegion.ExtractShardId = {
    case Envelope(id, _) ⇒ (id.hashCode() % numberOfShards).toString
  }
}