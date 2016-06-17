package proton.game.hermes

object DefaultHermesGameState {
  val defaultUpgrades = Map(
    HermesGameUpgrade.DEFAULT -> HermesGameUpgradeState(0, 0, 0),
    HermesGameUpgrade.LEVEL1 -> HermesGameUpgradeState(600, 2, 60),
    HermesGameUpgrade.LEVEL2 -> HermesGameUpgradeState(1200, 2, 150)
  )

  val defaultResearchOptions = Map(
    HermesGameResearch.DEFAULT -> HermesGameResearchState(0, 0, 0, 0, 0, 0),
    HermesGameResearch.GREEN -> HermesGameResearchState(5000, 2, 250, 25, 0, 0),
    HermesGameResearch.GRID -> HermesGameResearchState(5000, 2, 500, 0, 0, 0),
    HermesGameResearch.LOW_LATENCY -> HermesGameResearchState(5000, 2, 0, 50, 0, 0),
    HermesGameResearch.WAN_COMPRESSION -> HermesGameResearchState(5000, 2, 0, 0, 50, 0),
    HermesGameResearch.DB_REPLICATION -> HermesGameResearchState(5000, 2, 0, 0, 0, 50)
  )

  val defaultInitialState: HermesGamePrivateState = HermesGamePrivateState(0, 0, 100, 1000, 0, Map(
    HermesGameTier.Database -> HermesGameServerTierState(8, 2,
      HermesGameServerPerformanceState(
        Seq(
          HermesGameServerCapacityState(1, 1000, 100, isAtOverload = false),
          HermesGameServerCapacityState(1001, 1200, 90, isAtOverload = false),
          HermesGameServerCapacityState(1201, 1400, 50, isAtOverload = true),
          HermesGameServerCapacityState(1401, Int.MaxValue, 0, isAtOverload = false)
        ), Seq(
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.NA, 90),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.EU, 90),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.NA, 90),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.AP, 90),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.EU, 90),
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.AP, 90)
        ), Some(Seq(
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.NA, 80),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.EU, 80),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.NA, 70),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.AP, 70),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.EU, 70),
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.AP, 70)
        ))
      ), Map(
        HermesGameRegion.AP -> HermesGameServerRegionState(0, 0, 0, 0, Seq()),
        HermesGameRegion.EU -> HermesGameServerRegionState(1, 0, 0, 0, Seq()),
        HermesGameRegion.NA -> HermesGameServerRegionState(0, 0, 0, 0, Seq())
      )),
    HermesGameTier.Middle -> HermesGameServerTierState(4, 1,
      HermesGameServerPerformanceState(
        Seq(
          HermesGameServerCapacityState(1, 400, 100, isAtOverload = false),
          HermesGameServerCapacityState(401, 500, 90, isAtOverload = false),
          HermesGameServerCapacityState(501, 600, 50, isAtOverload = true),
          HermesGameServerCapacityState(601, Int.MaxValue, 0, isAtOverload = false)
        ), Seq(
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.NA, 90),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.EU, 90),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.NA, 80),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.AP, 80),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.EU, 80),
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.AP, 80)
        ), None
      ), Map(
        HermesGameRegion.AP -> HermesGameServerRegionState(1, 0, 0, 0, Seq()),
        HermesGameRegion.EU -> HermesGameServerRegionState(1, 0, 0, 0, Seq()),
        HermesGameRegion.NA -> HermesGameServerRegionState(1, 0, 0, 0, Seq())
      )),
    HermesGameTier.Web -> HermesGameServerTierState(2, 0,
      HermesGameServerPerformanceState(
        Seq(
          HermesGameServerCapacityState(1, 180, 100, isAtOverload = false),
          HermesGameServerCapacityState(181, 200, 90, isAtOverload = false),
          HermesGameServerCapacityState(201, 250, 50, isAtOverload = true),
          HermesGameServerCapacityState(251, Int.MaxValue, 0, isAtOverload = false)
        ), Seq(
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.NA, 70),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.EU, 70),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.NA, 50),
          HermesGameServerFailoverState(HermesGameRegion.NA, HermesGameRegion.AP, 50),
          HermesGameServerFailoverState(HermesGameRegion.AP, HermesGameRegion.EU, 50),
          HermesGameServerFailoverState(HermesGameRegion.EU, HermesGameRegion.AP, 50)
        ), None
      ), Map(
        HermesGameRegion.AP -> HermesGameServerRegionState(1, 0, 0, 0, Seq()),
        HermesGameRegion.EU -> HermesGameServerRegionState(1, 0, 0, 0, Seq()),
        HermesGameRegion.NA -> HermesGameServerRegionState(2, 0, 0, 0, Seq())
      ))
  ), HermesGameAppliedUpgradeState(HermesGameUpgrade.DEFAULT, -1), Map(HermesGameResearch.DEFAULT -> -1))
}
