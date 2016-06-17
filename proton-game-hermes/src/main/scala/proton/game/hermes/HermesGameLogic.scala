package proton.game.hermes

import akka.event.LoggingAdapter
import proton.game.hermes.HermesGameRegion._
import proton.game.hermes.HermesGameResearch._
import proton.game.hermes.HermesGameTier._
import proton.game.hermes.HermesGameUpgrade._

import scala.collection.mutable

trait HermesGameLogic {
  def log: LoggingAdapter

  def calculateNewPlayerState(shared: HermesSharedState, playerState: HermesGamePrivateState,
                              commands: Seq[HermesGameCommand]): HermesGamePrivateState = {

    val mutablePlayerState = playerState.toMutable.resetPlay

    commands.foreach {
      case AdjustServers(changes) => applyServerChanges(mutablePlayerState.serverTiers, changes)
      case ApplyResearch(research) => applyCost(
        applyResearch(shared, research, mutablePlayerState.profitAccumulated, mutablePlayerState.appliedResearch),
        mutablePlayerState)
      case Upgrade() => mutablePlayerState.upgrade = applyUpgrade(shared, mutablePlayerState.upgrade)
      case msg => log.error("the hermes command {} was not recognized.", msg)
    }

    applyChanges(shared, mutablePlayerState)

    val totalTransactions = calculateTransactions(shared, mutablePlayerState)

    val totalNodes = mutablePlayerState.serverTiers.aggregate(0)(
      (x, t) => x + t._2.regions.aggregate(0)(
        (y, r) => y + r._2.nodeCount,
        _ + _),
      _ + _)

    mutablePlayerState.costIncurred += totalNodes * mutablePlayerState.costPerServer
    mutablePlayerState.profitEarned =
      (mutablePlayerState.profitConstant * totalTransactions) - mutablePlayerState.costIncurred
    mutablePlayerState.profitAccumulated += mutablePlayerState.profitEarned

    mutablePlayerState.toImmutable
  }

  def applyServerChanges(serverTiers: Map[HermesGameTier, MutableHermesGameServerTierState],
                         changes: Seq[HermesGameServerAdjustment]) = {
    changes.foreach(c => if (c.count != 0) {
      serverTiers.get(c.tier).map(t => t.regions.get(c.region).map(s => {
        s.changes += new MutableHermesGameServerChangeState(c.count, if (c.count > 0) t.startTicks else t.shutdownTicks)
      }))
    })
  }

  def applyResearch(shared: HermesSharedState, research: HermesGameResearch, profit: Double,
                    appliedResearch: mutable.Map[HermesGameResearch, Int]): Double = {
    appliedResearch.get(research) match {
      case Some(ticks) => 0.0
      case None =>
        shared.researchOptions.get(research).map(r => {
          if (profit >= r.cost) {
            appliedResearch += research -> r.ticksRequired
            r.cost
          } else {
            0.0
          }
        }).getOrElse(0.0)
    }
  }

  def applyUpgrade(shared: HermesSharedState,
                   currentUpgrade: MutableHermesGameAppliedUpgradeState): MutableHermesGameAppliedUpgradeState = {
    if (currentUpgrade.ticks > 0) {
      currentUpgrade
    } else {
      val next = getNextUpgradeLevel(shared, currentUpgrade.level)

      if (next == currentUpgrade.level) currentUpgrade
      else
        new MutableHermesGameAppliedUpgradeState(next, shared.upgrades.get(next).map(u => u.ticksRequired).getOrElse(0))
    }
  }

  def getNextUpgradeLevel(shared: HermesSharedState, current: HermesGameUpgrade): HermesGameUpgrade = {
    var isNext = false
    var next = current

    HermesGameUpgrade.values.foreach(v => {
      if (isNext && shared.upgrades.contains(v)) {
        next = v
        isNext = false
      }
      else if (v == current) isNext = true
    })

    next
  }

  def applyCost(cost: Double, playersState: MutableHermesGamePrivateState) = {
    if (cost > 0) {
      playersState.profitAccumulated -= cost
      playersState.costIncurred += cost
    }
  }

  def applyChanges(shared: HermesSharedState, playersState: MutableHermesGamePrivateState) = {
    playersState.serverTiers.foreach {
      case (tier, tierState) => tierState.regions.foreach {
        case (region, regionState) => applyNodeChanges(regionState)
      }
    }

    applyResearchUpgrades(shared, playersState)
    applyUpgrades(shared, playersState)
  }

  def applyNodeChanges(state: MutableHermesGameServerRegionState) = {
    state.changes.foreach(c => {
      if (c.effectiveTicks == 0) {
        state.nodeCount += c.nodeCount

        if (state.nodeCount < 0)
          state.nodeCount = 0
      }

      c.effectiveTicks -= 1
    })

    state.changes --= state.changes.filter(c => c.effectiveTicks < 0)
  }

  def applyResearchUpgrades(shared: HermesSharedState, playersState: MutableHermesGamePrivateState) = {
    playersState.appliedResearch.foreach {
      case (research, ticks) =>
        if (ticks == 0) {
          shared.researchOptions.get(research).foreach(r => {
            if (r.costPerServerBenefit > 0) {
              playersState.costPerServer -= r.costPerServerBenefit

              if (playersState.costPerServer <= 0.1)
                playersState.costPerServer = 0.1
            }

            if (r.transactionBenefit > 0) {
              playersState.serverTiers.foreach {
                case (tier, tierState) => applyServerTierTransactionBenefit(tierState, r.transactionBenefit)
              }
            }

            if (r.failOverBenefit > 0) {
              playersState.serverTiers.foreach {
                case (tier, tierState) => applyServerTierFailoverBenefit(tierState, r.failOverBenefit)
              }
            }

            if (r.databaseReplicationBenefit > 0) {
              playersState.serverTiers.foreach {
                case (tier, tierState) =>
                  applyServerTierDatabaseReplicationBenefit(tierState, r.databaseReplicationBenefit)
              }
            }
          })
        }

        if (ticks >= 0) {
          playersState.appliedResearch(research) = ticks - 1
        }
    }
  }

  def applyServerTierFailoverBenefit(tier: MutableHermesGameServerTierState, benefit: Double) = {
    tier.performance.failoverLevels.zipWithIndex.foreach {
      case (state, i) =>
        var failurePercentage = 100.0 - state.successPercentage
        failurePercentage = (1.0 - benefit / 100.0) * failurePercentage
        state.successPercentage = 100.0 - failurePercentage
    }
  }

  def applyServerTierDatabaseReplicationBenefit(tier: MutableHermesGameServerTierState, benefit: Double) = {
    tier.performance.databaseReplicationLevels.foreach(l => l.zipWithIndex.foreach {
      case (state, i) =>
        var failurePercentage = 100.0 - state.successPercentage
        failurePercentage = (1.0 - benefit / 100.0) * failurePercentage
        state.successPercentage = 100 - failurePercentage
    })
  }

  def applyUpgrades(shared: HermesSharedState, playersState: MutableHermesGamePrivateState) = {
    if (playersState.upgrade.ticks == 0) {
      shared.upgrades.get(playersState.upgrade.level).foreach(s => {
        playersState.costPerServer += s.cost
        playersState.serverTiers.foreach {
          case (tier, tierState) => applyServerTierTransactionBenefit(tierState, s.transactionBenefit)
        }
      })
    }

    if (playersState.upgrade.ticks >= 0) {
      playersState.upgrade.ticks -= 1
    }
  }

  def applyServerTierTransactionBenefit(tier: MutableHermesGameServerTierState, benefit: Int) = {
    tier.performance.capacityLevels.sorted.zipWithIndex.foreach {
      case (state, i) =>
        state.lowerLimit = i * benefit
        state.upperLimit = (i + 1) * benefit
    }
  }

  def calculateTransactions(shared: HermesSharedState, playerState: MutableHermesGamePrivateState): Long = {
    var totalNumberOfTransactions = 0L

    shared.transactions.foreach(transactions => {
      var processingTransaction = transactions

      playerState.serverTiers.get(HermesGameTier.Web).foreach(tierState => {
        processingTransaction = calculateTierTransactions(processingTransaction, tierState)
      })

      playerState.serverTiers.get(HermesGameTier.Middle).foreach(tierState => {
        processingTransaction = calculateTierTransactions(processingTransaction, tierState)
      })

      playerState.serverTiers.get(HermesGameTier.Database).foreach(tierState => {
        processingTransaction = calculateTierTransactions(processingTransaction, tierState)

        transactions.foreach {
          case (region, count) => totalNumberOfTransactions +=
            calculateDatabaseReplicationTransactions(region, transactions, tierState)
        }
      })

    })

    totalNumberOfTransactions
  }

  def calculateTierTransactions(transactions: Map[HermesGameRegion, Int],
                                tierState: MutableHermesGameServerTierState): Map[HermesGameRegion, Int] =
    calculateTierPeakTransactions(
      calculateTierFailoverTransactions(
        calculateTierNormalTransactions(transactions, tierState), tierState), tierState)

  def calculateTierNormalTransactions(transactions: Map[HermesGameRegion, Int],
                                      tierState: MutableHermesGameServerTierState): Map[HermesGameRegion, Int] = {
    transactions.map {
      case (region, transactionCount) =>
        if (transactionCount > 0) {
          tierState.regions.get(region) match {
            case Some(state) =>
              state.transactionInputCount += transactionCount

              if (state.nodeCount <= 0) {
                region -> 0
              } else {
                var intermediateTransactionCount = transactionCount

                var overloaded = false
                tierState.performance.capacityLevels.sorted.foreach(level => if (!overloaded) {
                  if (level.isAtOverload) overloaded = true
                  val levelCapacity = (level.upperLimit - level.lowerLimit + 1) * state.nodeCount

                  if (intermediateTransactionCount > levelCapacity) {
                    state.transactionExecutedCount += levelCapacity
                    state.transactionSucceededCount += (levelCapacity * (level.successPercentage / 100)).toInt
                    intermediateTransactionCount -= levelCapacity
                  } else {
                    state.transactionExecutedCount += intermediateTransactionCount
                    state.transactionSucceededCount +=
                      (intermediateTransactionCount * (level.successPercentage / 100)).toInt
                    intermediateTransactionCount = 0
                  }
                })

                region -> intermediateTransactionCount
              }
            case None => region -> 0
          }
        } else {
          region -> 0
        }
    }
  }

  def calculateTierFailoverTransactions(transactions: Map[HermesGameRegion, Int],
                                        tierState: MutableHermesGameServerTierState): Map[HermesGameRegion, Int] = {
    transactions.map {
      case (region, transactionCount) =>
        if (transactionCount > 0) {
          tierState.regions.get(region) match {
            case Some(state) =>
              var intermediateTransactionCount = transactionCount
              val failOverRegions = tierState.performance.failoverLevels.filter(level => level.regionFrom == region)
              if (failOverRegions.size == 2) {
                if (failOverRegions.head.successPercentage > failOverRegions(1).successPercentage) {
                  intermediateTransactionCount =
                    calculateTierFailoverTransactionsForRegion(intermediateTransactionCount, tierState,
                      failOverRegions.head)
                  intermediateTransactionCount =
                    calculateTierFailoverTransactionsForRegion(intermediateTransactionCount, tierState,
                      failOverRegions(1))
                } else {
                  intermediateTransactionCount =
                    calculateTierFailoverTransactionsForRegion(intermediateTransactionCount, tierState,
                      failOverRegions(1))
                  intermediateTransactionCount =
                    calculateTierFailoverTransactionsForRegion(intermediateTransactionCount, tierState,
                      failOverRegions.head)
                }
              }

              region -> intermediateTransactionCount
            case None => region -> 0
          }
        } else {
          region -> 0
        }
    }
  }

  def calculateTierFailoverTransactionsForRegion(transactionCount: Int,
                                                 tierState: MutableHermesGameServerTierState,
                                                 failoverState: MutableHermesGameServerFailoverState): Int = {

    var intermediateTransactionCount = transactionCount

    tierState.regions.get(failoverState.regionTo).foreach(state => {
      if (capacityAvailableInRegion(tierState, state)) {
        intermediateTransactionCount = (intermediateTransactionCount * (failoverState.successPercentage / 100.0)).toInt

        if (intermediateTransactionCount > 0) {
          state.transactionInputCount += intermediateTransactionCount
          var totalTransactionCount = state.transactionExecutedCount

          var overloaded = false
          var isAtFailoverCapacityLevel = false
          tierState.performance.capacityLevels.sorted.foreach(level => if (!overloaded) {
            if (level.isAtOverload) overloaded = true
            val levelCapacity = (level.upperLimit - level.lowerLimit + 1) * state.nodeCount

            if (!isAtFailoverCapacityLevel) {
              if (totalTransactionCount >= levelCapacity) {
                totalTransactionCount -= levelCapacity
              } else {
                isAtFailoverCapacityLevel = true
                val availableLevelCapacity = levelCapacity - totalTransactionCount

                if (intermediateTransactionCount > availableLevelCapacity) {
                  state.transactionExecutedCount += availableLevelCapacity
                  state.transactionSucceededCount += (availableLevelCapacity * (level.successPercentage / 100)).toInt
                  intermediateTransactionCount -= availableLevelCapacity
                } else {
                  state.transactionExecutedCount += intermediateTransactionCount
                  state.transactionSucceededCount +=
                    (intermediateTransactionCount * (level.successPercentage / 100)).toInt
                  intermediateTransactionCount = 0
                  overloaded = true
                }
              }
            } else {
              if (intermediateTransactionCount > levelCapacity) {
                state.transactionExecutedCount += levelCapacity
                state.transactionSucceededCount += (levelCapacity * (level.successPercentage / 100)).toInt
                intermediateTransactionCount -= levelCapacity
              } else {
                state.transactionExecutedCount += intermediateTransactionCount
                state.transactionSucceededCount +=
                  (intermediateTransactionCount * (level.successPercentage / 100)).toInt
                intermediateTransactionCount = 0
                overloaded = true
              }
            }
          })
        }
      }
    })

    intermediateTransactionCount
  }

  def capacityAvailableInRegion(tierState: MutableHermesGameServerTierState,
                                state: MutableHermesGameServerRegionState): Boolean = {
    var overloaded = false
    var totalCapacity = 0
    tierState.performance.capacityLevels.sorted.foreach(level => if (!overloaded) {
      if (level.isAtOverload) overloaded = true
      totalCapacity += (level.upperLimit - level.lowerLimit + 1) * state.nodeCount
    })
    state.transactionExecutedCount < totalCapacity
  }

  def calculateTierPeakTransactions(transactions: Map[HermesGameRegion, Int],
                                    tierState: MutableHermesGameServerTierState): Map[HermesGameRegion, Int] = {
    transactions.map {
      case (region, transactionCount) =>
        if (transactionCount > 0) {
          tierState.regions.get(region) match {
            case Some(state) =>
              var intermediateTransactionCount = transactionCount
              var overloaded = false
              tierState.performance.capacityLevels.sorted.foreach(level => {
                if (overloaded) {
                  val levelCapacity = (level.upperLimit - level.lowerLimit + 1) * state.nodeCount
                  if (intermediateTransactionCount > levelCapacity) {
                    state.transactionExecutedCount += levelCapacity
                    state.transactionSucceededCount += (levelCapacity * (level.successPercentage / 100)).toInt
                    intermediateTransactionCount -= levelCapacity
                  } else {
                    state.transactionExecutedCount += intermediateTransactionCount
                    state.transactionSucceededCount +=
                      (intermediateTransactionCount * (level.successPercentage / 100)).toInt
                    intermediateTransactionCount = 0
                  }
                } else if (level.isAtOverload) {
                  overloaded = true
                }
              })

              region -> intermediateTransactionCount
            case None => region -> 0
          }
        } else {
          region -> 0
        }
    }
  }

  def calculateDatabaseReplicationTransactions(region: HermesGameRegion, transactions: Map[HermesGameRegion, Int],
                                               tierState: MutableHermesGameServerTierState): Int = {
    var totalTransactionCount = 0

    tierState.regions.get(region).foreach(regionState => {
      var intermediateTransactionCount = regionState.transactionSucceededCount

      if (regionState.nodeCount > 0) {
        val otherRegions = tierState.regions.filter {
          case (otherRegion, otherRegionState) => otherRegion != region
        }.toSeq

        if (otherRegions.size == 2) {
          var replicationRegion = Seq[MutableHermesGameServerFailoverState]()

          if (otherRegions.head._2.nodeCount > otherRegions(1)._2.nodeCount) {
            replicationRegion = otherRegions.flatMap {
              case (otherRegion, otherRegionState) =>
                tierState.performance.databaseReplicationLevels.flatMap(o =>
                  o.find(l => l.regionFrom != region && l.regionTo != region))
            }
          } else if (otherRegions.head._2.nodeCount > 0) {
            replicationRegion = otherRegions.flatMap {
              case (otherRegion, otherRegionState) =>
                tierState.performance.databaseReplicationLevels.flatMap(o =>
                  o.find(l => l.regionFrom == region && l.regionTo != otherRegions.head._1))
            }
          } else if (otherRegions(1)._2.nodeCount > 0) {
            replicationRegion = otherRegions.flatMap {
              case (otherRegion, otherRegionState) =>
                tierState.performance.databaseReplicationLevels.flatMap(o =>
                  o.find(l => l.regionFrom == region && l.regionTo != otherRegions.head._1))
            }
          }

          if (replicationRegion.nonEmpty) {
            intermediateTransactionCount =
              (intermediateTransactionCount * (replicationRegion.head.successPercentage / 100)).toInt
          }
        }

        totalTransactionCount = intermediateTransactionCount
      }
    })

    totalTransactionCount
  }
}
