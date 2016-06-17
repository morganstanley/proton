package proton.game.hermes

import java.util.UUID

import proton.game._

import scala.util.{Failure, Success, Try}

class HermesGameTicker(endpoint: GameEndPoint, eventBus: GameEventBus, repository: HermesGameFileRepository,
                       settings: HermesGameTickerModuleSettings)
  extends GameTicker[HermesGameTickerConfig, HermesSharedState, HermesGamePublicState,
    HermesGamePrivateState, HermesGameCommand](HermesGameMessage, endpoint, eventBus) with HermesGameLogic {

  private var gameFile: Option[HermesGameFile] = None

  override protected def continue: Boolean = gameFile.exists(g => sharedState.exists(s => s.tick < g.totalTicks))

  override protected def stateOrdering: Ordering[HermesGamePrivateState] = HermesGamePrivateStateOrdering

  private def getGameFile(config: HermesGameTickerConfig): Option[HermesGameFile] = {
    gameFile match {
      case opt@Some(_) => opt
      case None =>
        gameFile = repository.getById(config.gameFile)
        gameFile
    }
  }

  override protected def validateConfig(config: HermesGameTickerConfig): Try[HermesGameTickerConfig] = {
    super.validateConfig(config).flatMap(c => {
      if (getGameFile(config).exists(f => f.complete)) {
        Success(c)
      } else {
        Failure(ValidationException(s"The complete game file ${config.gameFile} does not exist.",
          HermesGameEvents.GameFileDoesNotExist))
      }
    })
  }

  override protected def validateCommand(playerId: UUID, state: HermesGamePrivateState, command: HermesGameCommand) = {
    assert(sharedState.isDefined)
    val shared = sharedState.get

    command match {
      case Upgrade() =>
        if (state.upgrade.ticks > 0) {
          Failure(ValidationException(
              s"The hermes command $command is not valid for game ${_id} because the upgrade to level ${state.upgrade.level} is still being applied.",
              HermesGameEvents.UpgradeInProgress))
        } else {
          val next = getNextUpgradeLevel(shared, state.upgrade.level)

          if (next == state.upgrade.level) {
            Failure(ValidationException(
                s"The hermes command $command is not valid for game ${_id} because there is no more infrastructure upgrades available.",
                HermesGameEvents.NoInfrastructureAvailable))
          } else {
            Success(command)
          }
        }
      case ApplyResearch(research) =>
        state.appliedResearch.get(research) match {
          case Some(ticks) =>
            if (ticks > 0) {
              Failure(ValidationException(
                  s"The hermes command $command is not valid for game ${_id} because research $research is still being applied.",
                  HermesGameEvents.ResearchBeingApplied))
            } else {
              Failure(ValidationException(
                  s"The hermes command $command is not valid for game ${_id} because research $research has already been applied.",
                  HermesGameEvents.ResearchAlreadyApplied))
            }
          case None =>
            shared.researchOptions.get(research) match {
              case Some(value) =>
                val profits = state.profitAccumulated
                if (profits >= value.cost) {
                  Success(command)
                } else {
                  Failure(ValidationException(
                      s"The hermes command $command is not valid for game ${_id} because you currently have $profits but require ${value.cost} to apply research $research.",
                      HermesGameEvents.CannotAffordResearch))
                }
              case None =>
                Failure(ValidationException(
                    s"The hermes command $command is not valid for game ${_id} because research $research is not available to be applied.",
                    HermesGameEvents.ResearchNotAvailable))
            }
        }
      case AdjustServers(changes) => Success(command)
      case _ => Failure(ValidationException(s"Not a hermes command.", HermesGameEvents.InvalidHermesCommand))
    }
  }

  override protected def initialSharedState(config: HermesGameTickerConfig): Option[HermesSharedState] = {
    gameFile = getGameFile(config)

    for (
      file <- gameFile;
      fileSettings <- file.settings
    ) yield new HermesSharedState(file.id, -1, None, None, fileSettings.upgrades, fileSettings.researchOptions)
  }

  protected override def initialPlayerPrivateState(playerId: UUID): Option[HermesGamePrivateState] = {
    gameFile.flatMap(game => game.settings.map(s => s.initialState.copy()))
  }

  override protected def play(state: (Option[HermesSharedState], Map[UUID, (HermesGamePrivateState, Seq[HermesGameCommand])])) = {
    for (
      game <- gameFile;
      shared <- state._1
    ) yield {
      val tick = shared.tick + 1
      val entry = game.getEntry(tick)

      val newSharedState = shared.copy(tick = tick, transactionTime = entry.map(e => e.time),
        transactions = entry.map(e => e.values))

      (Some(newSharedState), state._2.map {
        case (playerId, playerState) => playerId -> calculateNewPlayerState(newSharedState, playerState._1, playerState._2)
      })
    }
  }

  override protected def receiveTimeout = settings.gameTimeout
}
