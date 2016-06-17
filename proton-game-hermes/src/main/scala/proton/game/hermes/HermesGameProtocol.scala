package proton.game.hermes

import spray.json._
import proton.game.GameProtocol
import proton.game.hermes.HermesGameRegion.HermesGameRegion
import proton.game.hermes.HermesGameResearch.HermesGameResearch
import proton.game.hermes.HermesGameTickerModule._
import proton.game.hermes.HermesGameTickerModulePerRequest._
import proton.game.hermes.HermesGameTier.HermesGameTier
import proton.game.hermes.HermesGameUpgrade.HermesGameUpgrade

trait HermesGameProtocol extends GameProtocol {

  implicit object HermesGameUpgradeFormat extends JsonFormat[HermesGameUpgrade] {
    def write(obj: HermesGameUpgrade): JsValue = JsString(obj.toString)
    def read(json: JsValue): HermesGameUpgrade = json match {
      case JsString(str) => HermesGameUpgrade.withName(str)
      case _ => throw new DeserializationException("HermesGameUpgrade expected.")
    }
  }

  implicit object HermesGameResearchFormat extends JsonFormat[HermesGameResearch] {
    def write(obj: HermesGameResearch): JsValue = JsString(obj.toString)
    def read(json: JsValue): HermesGameResearch = json match {
      case JsString(str) => HermesGameResearch.withName(str)
      case _ => throw new DeserializationException("HermesGameResearch expected.")
    }
  }

  implicit object HermesGameRegionFormat extends JsonFormat[HermesGameRegion] {
    def write(obj: HermesGameRegion): JsValue = JsString(obj.toString)
    def read(json: JsValue): HermesGameRegion = json match {
      case JsString(str) => HermesGameRegion.withName(str)
      case _ => throw new DeserializationException("HermesGameRegion expected.")
    }
  }

  implicit object HermesGameTierFormat extends JsonFormat[HermesGameTier] {
    def write(obj: HermesGameTier): JsValue = JsString(obj.toString)
    def read(json: JsValue): HermesGameTier = json match {
      case JsString(str) => HermesGameTier.withName(str)
      case _ => throw new DeserializationException("HermesGameTier expected.")
    }
  }

  implicit val hermesGameServerAdjustmentFormat = rootFormat(jsonFormat3(HermesGameServerAdjustment))
  implicit val advertisementDefinitionFormat = rootFormat(jsonFormat2(AdvertisementDefinition))
  implicit val advertisementNameChangeFormat = rootFormat(jsonFormat1(AdvertisementNameChange))
  implicit val advertisementVisibilityChangeFormat = rootFormat(jsonFormat1(AdvertisementVisibilityChange))
  implicit val gameFileCompleteChangeFormat = rootFormat(jsonFormat1(GameFileCompleteChange))
  implicit val hermesGameFileEntryFormat = rootFormat(jsonFormat2(HermesGameFileEntry))

  implicit object HermesGameCommandFormat extends RootJsonFormat[HermesGameCommand] {
    def write(c: HermesGameCommand) = c match {
      case Upgrade() => JsObject("command" -> JsString("Upgrade"))
      case ApplyResearch(research) => JsObject(
        "command" -> JsString("ApplyResearch"),
        "research" -> research.toJson
      )
      case AdjustServers(changes) => JsObject(
        "command" -> JsString("AdjustServers"),
        "changes" -> changes.toJson
      )
    }
    def read(value: JsValue) = {
      val jsObject = value.asJsObject
      jsObject.getFields("command") match {
        case Seq(JsString("Upgrade")) => Upgrade()
        case Seq(JsString("ApplyResearch")) => jsObject.getFields("research").headOption match {
          case Some(research) => ApplyResearch(research.convertTo[HermesGameResearch])
          case None => throw new DeserializationException("Research must be specified.")
        }
        case Seq(JsString("AdjustServers")) => jsObject.getFields("changes").headOption match {
          case Some(changes) => AdjustServers(changes.convertTo[Seq[HermesGameServerAdjustment]])
          case None => throw new DeserializationException("Server changes must be specified.")
        }
        case _ => throw new DeserializationException("Command not recognized.")
      }
    }
  }

  implicit val hermesGamePublicStateFormat = rootFormat(jsonFormat2(HermesGamePublicState))
  implicit val hermesGameServerFailoverStateFormat = rootFormat(jsonFormat3(HermesGameServerFailoverState))
  implicit val hermesGameServerCapacityStateFormat = rootFormat(jsonFormat4(HermesGameServerCapacityState))
  implicit val hermesGameServerPerformanceStateFormat = rootFormat(jsonFormat3(HermesGameServerPerformanceState))
  implicit val hermesGameServerChangeStateFormat = rootFormat(jsonFormat2(HermesGameServerChangeState))
  implicit val hermesGameServerRegionStateFormat = rootFormat(jsonFormat5(HermesGameServerRegionState))
  implicit val hermesGameServerTierStateFormat = rootFormat(jsonFormat4(HermesGameServerTierState))
  implicit val hermesGameAppliedUpgradeStateFormat = rootFormat(jsonFormat2(HermesGameAppliedUpgradeState))
  implicit val hermesGamePrivateStateFormat = rootFormat(jsonFormat(HermesGamePrivateState,
    "profitAccumulated", "profitEarned", "profitConstant", "costPerServer", "costIncurred", "serverTiers", "upgrade",
    "appliedResearch"))
  implicit val hermesGameUpgradeStateFormat = rootFormat(jsonFormat3(HermesGameUpgradeState))
  implicit val hermesGameResearchStateFormat = rootFormat(jsonFormat6(HermesGameResearchState))
  implicit val hermesSharedStateFormat = rootFormat(jsonFormat6(HermesSharedState))

  implicit val hermesGameConfigFactoryFormat = rootFormat(jsonFormat5(HermesGameTickerConfigFactory))

  implicit val hermesGameFileSettings = rootFormat(jsonFormat3(HermesGameFileSettings))

  implicit val hermesGameFileAdvertisementResultFormat = rootFormat(jsonFormat4(HermesGameFileAdvertisementResult))
  implicit val hermesGameTickerModulePerRequestGetResultsFormat =
    rootFormat(jsonFormat1(HermesGameTickerModulePerRequestGetResults))
  implicit val hermesGameTickerModulePerRequestGetEntriesResultsFormat =
    rootFormat(jsonFormat1(HermesGameTickerModulePerRequestGetEntriesResults))
  implicit val hermesGameTickerModulePerRequestDeleteResultFormat =
    rootFormat(jsonFormat1(HermesGameTickerModulePerRequestDeleteResult))
  implicit val hermesGameTickerModulePerRequestDetailsResult =
    rootFormat(jsonFormat11(HermesGameTickerModulePerRequestDetailsResult))
}

object HermesGameProtocol extends HermesGameProtocol
