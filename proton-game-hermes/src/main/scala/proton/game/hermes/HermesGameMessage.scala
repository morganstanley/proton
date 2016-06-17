package proton.game.hermes

import java.time.LocalDateTime
import java.util.UUID

import proton.game.hermes.HermesGameRegion.HermesGameRegion
import proton.game.hermes.HermesGameResearch.HermesGameResearch
import proton.game.hermes.HermesGameTier.HermesGameTier
import proton.game.hermes.HermesGameUpgrade.HermesGameUpgrade
import proton.game.{GamePrivateState, GamePublicState, GameSharedState, GameMessage}

import scala.collection.mutable.ListBuffer

case class HermesGamePublicState(profitAccumulated: Double, profitEarned: Double) extends GamePublicState

case class HermesGameServerFailoverState(regionFrom: HermesGameRegion, regionTo: HermesGameRegion,
                                         successPercentage: Double) {
  def toMutable = new MutableHermesGameServerFailoverState(regionFrom, regionTo, successPercentage)
}

case class HermesGameServerCapacityState(lowerLimit: Int, upperLimit: Int, successPercentage: Double,
                                         isAtOverload: Boolean) {
  def toMutable = new MutableHermesGameServerCapacityState(lowerLimit, upperLimit, successPercentage, isAtOverload)
}

case class HermesGameServerPerformanceState(capacityLevels: Seq[HermesGameServerCapacityState],
                                            failoverLevels: Seq[HermesGameServerFailoverState],
                                            databaseReplicationLevels: Option[Seq[HermesGameServerFailoverState]]) {
  def toMutable = new MutableHermesGameServerPerformanceState(capacityLevels.map(l => l.toMutable),
    failoverLevels.map(l => l.toMutable), databaseReplicationLevels.map(o => o.map(l => l.toMutable)))
}

case class HermesGameServerChangeState(nodeCount: Int, effectiveTicks: Int) {
  def toMutable = new MutableHermesGameServerChangeState(nodeCount, effectiveTicks)
}

case class HermesGameServerRegionState(nodeCount: Int, transactionInputCount: Int, transactionExecutedCount: Int,
                                       transactionSucceededCount: Int, changes: Seq[HermesGameServerChangeState]) {
  def toMutable = new MutableHermesGameServerRegionState(nodeCount, transactionInputCount, transactionExecutedCount,
    transactionSucceededCount, ListBuffer() ++= changes.map(c => c.toMutable))
}

case class HermesGameServerTierState(startTicks: Int, shutdownTicks: Int, performance: HermesGameServerPerformanceState,
                                     regions: Map[HermesGameRegion, HermesGameServerRegionState]) {
  def toMutable = new MutableHermesGameServerTierState(startTicks, shutdownTicks, performance.toMutable,
    regions.map(r => (r._1, r._2.toMutable)))
}

case class HermesGameAppliedUpgradeState(level: HermesGameUpgrade, ticks: Int) {
  def toMutable = new MutableHermesGameAppliedUpgradeState(level, ticks)
}

case class HermesGamePrivateState(profitAccumulated: Double,
                                  profitEarned: Double,
                                  profitConstant: Double,
                                  costPerServer: Double,
                                  costIncurred: Double,
                                  serverTiers: Map[HermesGameTier, HermesGameServerTierState],
                                  upgrade: HermesGameAppliedUpgradeState,
                                  appliedResearch: Map[HermesGameResearch, Int])
  extends GamePrivateState[HermesGamePublicState] {

  override lazy val public: HermesGamePublicState = new HermesGamePublicState(profitAccumulated, profitEarned)

  def toMutable = new MutableHermesGamePrivateState(profitAccumulated, profitEarned, profitConstant, costPerServer,
    costIncurred, serverTiers.map(r => (r._1, r._2.toMutable)), upgrade.toMutable,
    scala.collection.mutable.Map(appliedResearch.toSeq: _*))
}

case class HermesGameUpgradeState(cost: Double, ticksRequired: Int, transactionBenefit: Int)

case class HermesGameResearchState(cost: Double, ticksRequired: Int, costPerServerBenefit: Double,
                                   transactionBenefit: Int, failOverBenefit: Int,
                                   databaseReplicationBenefit: Int)

case class HermesSharedState(gameFile: UUID, tick: Int, transactionTime: Option[LocalDateTime],
                             transactions: Option[Map[HermesGameRegion, Int]],
                             upgrades: Map[HermesGameUpgrade, HermesGameUpgradeState],
                             researchOptions: Map[HermesGameResearch, HermesGameResearchState])
  extends GameSharedState

object HermesGamePrivateStateOrdering extends Ordering[HermesGamePrivateState] {
  override def compare(x: HermesGamePrivateState, y: HermesGamePrivateState): Int =
    x.profitAccumulated.compare(y.profitAccumulated)
}

class MutableHermesGameServerFailoverState(val regionFrom: HermesGameRegion, val regionTo: HermesGameRegion,
                                           var successPercentage: Double) {
  def toImmutable = HermesGameServerFailoverState(regionFrom, regionTo, successPercentage)
}

class MutableHermesGameServerCapacityState(var lowerLimit: Int, var upperLimit: Int, var successPercentage: Double, var isAtOverload: Boolean)
  extends Ordered[MutableHermesGameServerCapacityState] {

  def toImmutable = HermesGameServerCapacityState(lowerLimit, upperLimit, successPercentage, isAtOverload)

  override def compare(that: MutableHermesGameServerCapacityState): Int = lowerLimit.compareTo(that.lowerLimit)
}

class MutableHermesGameServerPerformanceState(val capacityLevels: Seq[MutableHermesGameServerCapacityState],
                                              val failoverLevels: Seq[MutableHermesGameServerFailoverState],
                                              val databaseReplicationLevels: Option[Seq[MutableHermesGameServerFailoverState]]) {

  def toImmutable = HermesGameServerPerformanceState(capacityLevels.map(l => l.toImmutable),
    failoverLevels.map(l => l.toImmutable), databaseReplicationLevels.map(o => o.map(l => l.toImmutable)))
}

class MutableHermesGameServerChangeState(val nodeCount: Int, var effectiveTicks: Int) {
  def toImmutable = HermesGameServerChangeState(nodeCount, effectiveTicks)
}

class MutableHermesGameServerRegionState(var nodeCount: Int, var transactionInputCount: Int,
                                         var transactionExecutedCount: Int,
                                         var transactionSucceededCount: Int,
                                         val changes: ListBuffer[MutableHermesGameServerChangeState]) {

  def toImmutable = HermesGameServerRegionState(nodeCount, transactionInputCount, transactionExecutedCount,
    transactionSucceededCount, changes.map(c => c.toImmutable))
}

class MutableHermesGameServerTierState(var startTicks: Int, var shutdownTicks: Int,
                                       var performance: MutableHermesGameServerPerformanceState,
                                       val regions: Map[HermesGameRegion, MutableHermesGameServerRegionState]) {

  def toImmutable = HermesGameServerTierState(startTicks, shutdownTicks, performance.toImmutable,
    regions.map(r => (r._1, r._2.toImmutable)))
}

class MutableHermesGameAppliedUpgradeState(val level: HermesGameUpgrade, var ticks: Int) {
  def toImmutable = HermesGameAppliedUpgradeState(level, ticks)
}

class MutableHermesGamePrivateState(var profitAccumulated: Double, var profitEarned: Double, var profitConstant: Double,
                                    var costPerServer: Double, var costIncurred: Double,
                                    val serverTiers: Map[HermesGameTier, MutableHermesGameServerTierState],
                                    var upgrade: MutableHermesGameAppliedUpgradeState,
                                    val appliedResearch: scala.collection.mutable.Map[HermesGameResearch, Int]) {

  def toImmutable = HermesGamePrivateState(profitAccumulated, profitEarned, profitConstant, costPerServer, costIncurred,
    serverTiers.map(t => (t._1, t._2.toImmutable)), upgrade.toImmutable, appliedResearch.toMap)

  def resetPlay = {
    profitEarned = 0
    costIncurred = 0
    serverTiers.foreach { case (tier, tierState) => tierState.regions.foreach {
      case (region, regionState) =>
        regionState.transactionExecutedCount = 0
        regionState.transactionSucceededCount = 0
    }
    }
    this
  }
}

object HermesGameMessage
  extends GameMessage[HermesGameTickerConfig, HermesSharedState, HermesGamePublicState, HermesGamePrivateState,
    HermesGameCommand]
