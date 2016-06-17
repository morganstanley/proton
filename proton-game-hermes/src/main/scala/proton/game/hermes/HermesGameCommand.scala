package proton.game.hermes

import proton.game.GameCommand
import proton.game.hermes.HermesGameRegion.HermesGameRegion
import proton.game.hermes.HermesGameResearch.HermesGameResearch
import proton.game.hermes.HermesGameTier.HermesGameTier

object HermesGameTier extends Enumeration {
  type HermesGameTier = Value
  val Web, Middle, Database = Value
}

object HermesGameRegion extends Enumeration {
  type HermesGameRegion = Value
  val NA, EU, AP = Value
}

object HermesGameResearch extends Enumeration {
  type HermesGameResearch = Value
  val DEFAULT, GRID, GREEN, LOW_LATENCY, WAN_COMPRESSION, DB_REPLICATION = Value
}

object HermesGameUpgrade extends Enumeration {
  type HermesGameUpgrade = Value
  val DEFAULT, LEVEL1, LEVEL2 = Value
}

case class HermesGameServerAdjustment(tier: HermesGameTier, region: HermesGameRegion, count: Int)

sealed trait HermesGameCommand extends GameCommand
case class AdjustServers(changes: Seq[HermesGameServerAdjustment]) extends HermesGameCommand
case class ApplyResearch(research: HermesGameResearch) extends HermesGameCommand
case class Upgrade() extends HermesGameCommand