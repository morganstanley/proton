package proton.game.hermes

import proton.game.hermes.HermesGameResearch._
import proton.game.hermes.HermesGameUpgrade._

case class HermesGameFileSettings(upgrades: Map[HermesGameUpgrade, HermesGameUpgradeState],
                                  researchOptions: Map[HermesGameResearch, HermesGameResearchState],
                                  initialState: HermesGamePrivateState) {
  def this() = this(DefaultHermesGameState.defaultUpgrades,
    DefaultHermesGameState.defaultResearchOptions, DefaultHermesGameState.defaultInitialState)
}