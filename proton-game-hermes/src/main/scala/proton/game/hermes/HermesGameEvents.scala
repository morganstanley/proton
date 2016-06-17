package proton.game.hermes

object HermesGameEvents {
  val GameFileDoesNotExist = 1001
  val UpgradeInProgress = 1002
  val NoInfrastructureAvailable = 1003
  val ResearchBeingApplied = 1004
  val ResearchAlreadyApplied = 1005
  val CannotAffordResearch = 1006
  val ResearchNotAvailable = 1007
  val InvalidHermesCommand = 1008

  val AdvertisementAlreadyExists = 1101
  val AdvertisementDoesNotExist = 1102

  val MarkedComplete = 1201
  val CreationIncomplete = 1202
  val NotConfiguredCorrectly = 1203
  val NoEntries = 1204
  val PreviousEntriesNotFound = 1205
  val EntryNotSequential = 1206
  val AddInitialEntriesFailed = 1207

  val GameFileNotComplete = 1301
  val CouldNotSearchNames = 1302
}
