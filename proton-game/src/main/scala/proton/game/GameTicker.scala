package proton.game

import java.time._
import java.util.UUID

import akka.actor._
import akka.cluster.sharding.ShardRegion.Passivate
import akka.event.LoggingReceive
import akka.persistence.{PersistentActor, RecoveryCompleted}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.{Failure, Sorting, Success, Try}


abstract class GameTicker[TConfig <: GameTickerConfig, TSharedState <: GameSharedState, TPublicState <: GamePublicState,
TPrivateState <: GamePrivateState[TPublicState], TCommand <: GameCommand]
(message: GameMessage[TConfig, TSharedState, TPublicState, TPrivateState, TCommand], endpoint: GameEndPoint,
 eventBus: GameEventBus) extends PersistentActor with ActorLogging {

  import GameStatus._
  import context._
  import message._

  protected val _id: UUID = UUID.fromString(self.path.name)
  private val _topic = "game-" + _id.toString
  private val _playersState = mutable.Map[UUID, (TPrivateState, ListBuffer[TCommand])]()
  private val _players = mutable.Map[UUID, Player]()
  private val _unjoined = mutable.HashSet[UUID]()
  private val _joined = mutable.HashSet[UUID]()
  private val _playerGameStateOrdering = new PlayerGamePrivateStateOrdering(stateOrdering)
  private var _status: GameStatus = GameNotConfigured
  private var _sharedState: Option[TSharedState] = None
  private var _config: Option[TConfig] = None
  private var _ticker: Option[Cancellable] = None
  private var _timeout: Option[Cancellable] = None

  setReceiveTimeout(receiveTimeout)

  override def receiveRecover = {
    case RecoveryCompleted =>
      _status match {
        case GameRunning => startFirstTick()
        case GameReady => startFirstTick()
        case GameConfigured if _joined.nonEmpty => startTimeout()
        case _ =>
      }
    case event: GameEvent => updateState(event)
  }

  def updateState(e: GameEvent) = e match {
    case Configured(config) =>
      _config = Some(config)
      _status = GameConfigured
      config.players.foreach(p => {
        _players += (p.id -> p)
        _unjoined += p.id
      })
    case Joined(playerId) =>
      _unjoined -= playerId
      _joined += playerId

      if (_unjoined.isEmpty) {
        _status = GameReady
      }
    case Started() =>
      if (_status != GameStopped) {
        init()
      }
      _status = GameRunning
    case Played(playerId, command) =>
      _playersState(playerId)._2 += command
    case Stopped() =>
      _status = GameStopped
    case Cleared() =>
      _status = GameConfigured
    case Transitioned() =>
      val newStateOpt = play((_sharedState, _playersState.toMap))

      newStateOpt.foreach(newState => {
        _sharedState = newState._1
        newState._2.foreach(kv =>
          if (_playersState.contains(kv._1)) {
            _playersState(kv._1) = (kv._2, ListBuffer[TCommand]())
          }
        )
      })

      if (!continue) {
        _status = GameCompleted
      }
    case TimedOut() =>
      if (_joined.size > 1) {
        _status = GameCompleted
      } else {
        _status = GameReady
      }
  }

  private def init() = {
    assert(_config.isDefined)

    _sharedState = initialSharedState(_config.get)
    _playersState.clear()

    _joined.foreach(p => {
      initialPlayerPrivateState(p).foreach(ps => {
        _playersState += (p ->(ps, ListBuffer[TCommand]()))
      })
    })

    _status = GameRunning
  }

  private def startFirstTick() = {
    cancelTimeout()
    startNextTick()
  }

  private def startNextTick() = {
    _config.foreach(c => {
      val tickDuration = c.tickDuration
      log.debug("Scheduling tick in {}.", tickDuration)
      _ticker = Some(system.scheduler.scheduleOnce(tickDuration, self, Transition()))
    })
  }

  private def startTimeout() = {
    cancelTimeout()

    _config.foreach(c => {
      c.timeoutDuration.foreach(t => {
        log.debug("Starting timeout of {} for all players to join.", t)
        _timeout = Some(system.scheduler.scheduleOnce(t, self, Timeout()))
      })
    })
  }

  private def cancelTimeout() = {
    _timeout.foreach(t => {
      log.debug("Cancelling timeout as ticking is about to begin.")
      t.cancel()
    })
    _timeout = None
  }

  override def receiveCommand: Receive = LoggingReceive {
    case GetStatus() =>
      sender ! StatusResult(_id, gameMeta)
    case GetState() =>
      sender ! StateResult(_id, sortedPlayersGameState.map(s => s.public))
    case GetPlayer(playerId) =>
      val player = _players.get(playerId)
      player match {
        case Some(value) => sender ! PlayerResult(_id, value)
        case None => sender ! Validation(s"Player $playerId is not associated with game ${_id}.",
          GameEvents.PlayerInvalid)
      }
    case Configure(config) =>
      if (_status == GameNotConfigured) {
        validateConfig(config) match {
          case Success(validated) =>
            persist(Configured(validated))(e => {
              updateState(e)
              log.info(s"Game {} configured.", _id)
              sender ! ConfiguredResult(_id, gameMeta)
            })
          case Failure(ex) => ex match {
            case ex: ValidationException => sender ! ex.validation
            case ex: Exception => sender ! Validation(s"Failed to configure game ${_id}.",
              GameEvents.ConfigInvalid, Some(ex))
          }
        }
      } else {
        sender ! Validation(s"Expected state GameNotConfigured but was ${_status} for game ${_id}.",
          GameEvents.StateInvalid)
      }
    case Join(playerId) =>
      if (_joined.contains(playerId)) {
        sender ! PlayerJoinedResult(_id, playerId, newlyJoined = false)
      } else if (_status == GameConfigured) {
        val playerOpt = _players.get(playerId)

        playerOpt match {
          case Some(player) =>
            if (!_unjoined.contains(playerId)) {
              sender ! Validation(s"Player $playerId is not associated with game ${_id}.",
                GameEvents.PlayerInvalid)
            } else if (!canJoin) {
              sender ! Validation(s"The game ${_id} will be open to join at $startTime.",
                GameEvents.NotOpen)
            } else {
              persist(Joined(playerId))(e => {
                updateState(e)
                log.info("Player {} joined game {}.", playerId, _id)

                if (_status == GameReady) {
                  log.info("Game {} is ready as all players have joined.", _id)
                  self ! Start()
                } else {
                  startTimeout()
                }

                sender ! PlayerJoinedResult(_id, playerId, newlyJoined = true)
                eventBus.publish(Envelope(_id, GameNotifyJoined(player.identity)))
              })
            }
          case None => sender ! Validation(s"Player $playerId is not associated with game ${_id}.",
            GameEvents.PlayerInvalid)
        }
      } else {
        sender ! Validation(s"Expected state GameConfigured but was ${_status} for game ${_id}.",
          GameEvents.StateInvalid)
      }
    case Play(playerId, command) =>
      if (_status == GameRunning) {
        val currentStateOpt = _playersState.get(playerId)
        currentStateOpt match {
          case Some(currentState) =>
            validateCommand(playerId, currentState._1, command) match {
              case Success(validated) =>
                persist(Played(playerId, validated))(e => {
                  updateState(e)
                  log.debug("Player {} played command {} in game {}.", playerId, command, _id)
                  sender ! PlayedResult(_id, currentState._2)
                })
              case Failure(ex) => ex match {
                case ex: ValidationException => sender ! ex.validation
                case ex: Exception => sender ! Validation(
                  s"The command $command is invalid for game ${_id}.", GameEvents.CommandInvalid, Some(ex))
              }
            }
          case None => sender ! Validation(s"Player $playerId is not associated with game ${_id}.",
            GameEvents.PlayerInvalid)
        }
      } else {
        sender ! Validation(s"Expected state GameRunning but was ${_status} for game ${_id}.",
          GameEvents.StateInvalid)
      }
    case Start() =>
      val canStartStates = Seq(GameConfigured, GameReady, GameStopped)
      if (canStartStates.contains(_status)) {
        persist(Started())(e => {
          updateState(e)
          log.info("Game {} started.", _id)

          if (_status == GameRunning) {
            startFirstTick()
          }

          sender ! StartedResult(_id, gameMeta)
          eventBus.publish(Envelope(_id, GameNotifyStarted()))
        })
      } else {
        sender ! Validation(
          s"Expected state to be either GameConfigured, GameReady, GameStopped but was ${_status} for game ${_id}.",
          GameEvents.StateInvalid)
      }
    case Stop() =>
      if (_status == GameRunning) {
        persist(Stopped())(e => {
          updateState(e)
          log.info("Game {} stopped.", _id)

          _ticker.foreach(_.cancel())
          _ticker = None

          sender ! StoppedResult(_id, gameMeta)
          eventBus.publish(Envelope(_id, GameNotifyStopped()))
        })
      } else {
        sender ! Validation(s"Expected state GameRunning but was ${_status} for game ${_id}.",
          GameEvents.StateInvalid)
      }
    case Clear() =>
      if (_status == GameStopped) {
        persist(Cleared())(e => {
          updateState(e)
          log.info("Game {} reset to configured state.", _id)

          sender ! ClearedResult(_id, gameMeta)
          eventBus.publish(Envelope(_id, GameNotifyCleared()))
        })
      } else {
        sender ! Validation(s"Expected state GameStopped but was ${_status} for game ${_id}.",
          GameEvents.StateInvalid)
      }
    case Transition() if _status == GameRunning =>
      persist(Transitioned())(e => {
        updateState(e)
        log.debug("Game {} progressed one step.", _id)
        if (_status == GameRunning) {
          eventBus.publish(Envelope(_id, GameNotifyTransitioned(GameState(_sharedState, sortedPlayersGameState))))
          startNextTick()
        } else {
          log.info("Game {} completed.", _id)
          eventBus.publish(Envelope(_id, GameNotifyCompleted(GameState(_sharedState, sortedPlayersGameState))))
        }
      })
    case Timeout() if _status == GameConfigured =>
      persist(TimedOut())(e => {
        updateState(e)
        log.warning("Game {} timed out with {} players.", _id, _joined.size)
        eventBus.publish(Envelope(_id, GameNotifyTimedOut()))
      })
    case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)
  }

  protected def validateConfig(config: TConfig): Try[TConfig] = {
    val min = config.minPlayers.getOrElse(0)
    val max = config.maxPlayers.getOrElse(Int.MaxValue)
    val size = config.players.size

    if (size < min || size > max) {
      Failure(ValidationException(s"The number of players must be between $min and $max.",
        GameEvents.WrongNumberOfPlayers))
    } else {
      Success(config)
    }
  }

  private def gameMeta: GameMeta = {
    if (_status == GameNotConfigured) {
      GameMeta(_status, endpoint.connectionString)
    } else {
      GameMeta(_status, endpoint.connectionString,
        Some(_joined.flatMap(id => _players.get(id).map(p => p.identity)).toSeq),
        Some(_unjoined.flatMap(id => _players.get(id).map(p => p.identity)).toSeq), startTime)
    }
  }

  private def startTime: Option[LocalDateTime] = _config.map {
    _.startTime
  } getOrElse None

  private def canJoin: Boolean = startTime.forall(_.isBefore(LocalDateTime.now))

  private def sortedPlayersGameState: Seq[PlayerGamePrivateState] = {
    val stateArray = _playersState.map(s => playerGameState(s._1, s._2._1)).toArray
    Sorting.quickSort(stateArray)(_playerGameStateOrdering)
    stateArray
  }

  private def playerGameState(playerId: UUID,
                              state: TPrivateState): PlayerGamePrivateState =
    playerGameState(_players.get(playerId).map(p => p.identity).getOrElse(PlayerIdentity(playerId, "")), state)

  private def playerGameState(playerIdentity: PlayerIdentity, state: TPrivateState): PlayerGamePrivateState =
    new PlayerGamePrivateState(playerIdentity.id, playerIdentity.name, state)

  protected def config = _config

  protected def sharedState = _sharedState

  protected def initialSharedState(config: TConfig): Option[TSharedState]

  protected def stateOrdering: Ordering[TPrivateState]

  protected def validateCommand(playerId: UUID, state: TPrivateState, command: TCommand): Try[TCommand]

  protected def initialPlayerPrivateState(playerId: UUID): Option[TPrivateState]

  protected def play(state: (Option[TSharedState], Map[UUID, (TPrivateState, Seq[TCommand])])): Option[
    (Option[TSharedState], Map[UUID, TPrivateState])]

  protected def continue: Boolean

  protected def receiveTimeout = 30.minutes

  override val persistenceId = "ticker-" + _id.toString
}
