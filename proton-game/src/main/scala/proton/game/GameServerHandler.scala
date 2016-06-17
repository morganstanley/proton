package proton.game

import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import akka.io.Tcp.{ConnectionClosed, Received, Write}
import akka.util.ByteString
import proton.game.GameServerHandler.GameServerHandlerMode.GameServerHandlerMode
import proton.game.GameServerHandler.GameServerHandlerResponseType.GameServerHandlerResponseType
import spray.json.JsonFormat

import scala.collection.mutable.ListBuffer

object GameServerHandler {
  object GameServerHandlerMode extends Enumeration {
    type GameServerHandlerMode = Value
    val Observer, Player = Value
  }

  object GameServerHandlerResponseType extends Enumeration {
    type GameServerHandlerResponseType = Value
    val Initialized, CommandAck, Transition, Complete, Error = Value
  }

  sealed trait GameServerHandlerMessage
  case class PlayerAuth(playerId: UUID, time: LocalDateTime, signature: String) extends GameServerHandlerMessage
  case class Initialize(gameId: UUID, mode: GameServerHandlerMode, auth: Option[PlayerAuth] = None)
    extends GameServerHandlerMessage
}

class GameServerHandler[TConfig <: GameConfig, TSharedState <: GameSharedState : JsonFormat,
TPublicState <: GamePublicState : JsonFormat, TPrivateState <: GamePrivateState[TPublicState] : JsonFormat,
TCommand <: GameCommand : JsonFormat](gameModule: GameModule, eventBus: GameEventBus,
                                      message: GameMessage[TConfig, TSharedState, TPublicState, TPrivateState, TCommand],
                                      connection: ActorRef, remote: InetSocketAddress)
  extends Actor with ActorLogging with GameProtocol {

  import context._
  import GameServerHandler._
  import message._
  import message.GameMessageProtocol._
  import spray.json._

  private val _gameRegion: ActorRef = ClusterSharding(system).shardRegion(gameModule.name)
  private val _defaultMaximumLineBytes = 10240
  private var _initialize: Option[Initialize] = None
  private var _buffer = ByteString.empty
  private var _nextPossibleMatch = 0

  def commonReceive: Receive = LoggingReceive {
    case closed: ConnectionClosed => context stop self
    case GameNotifyTransitioned(state) =>
      sendGameState(GameServerHandlerResponseType.Transition, state,
        Message("The game has progressed one step.", GameEvents.Transitioned))
    case GameNotifyCompleted(state) =>
      sendGameState(GameServerHandlerResponseType.Complete, state,
        Message("The game has finished.", GameEvents.Complete))
      context stop self
  }

  def observerReceive: Receive = commonReceive orElse {
    case Received(data) => buffer(data, (utf8Data) => {
      send(Validation("An observer cannot add commands.", GameEvents.ObserverCommandReceived))
    })
  }

  def playerReceive: Receive = commonReceive orElse {
    case Received(data) => buffer(data, (utf8Data) => {
      val json = utf8Data.parseJson
      val command = json.convertTo[TCommand]

      for (
        initialize <- _initialize;
        auth <- initialize.auth
      ) yield _gameRegion ! Envelope(initialize.gameId, Play(auth.playerId, command))
    })
    case PlayedResult(id, commands) =>
      send(GameServerHandlerResponseMessage(GameServerHandlerResponseType.CommandAck, commands = Some(commands)))
  }

  override def receive: Receive = LoggingReceive {
    case Received(data) => buffer(data, (utf8Data) => if (_initialize.isEmpty) {
      val json = utf8Data.parseJson
      val initialize = json.convertTo[Initialize]

      _initialize = Some(initialize)
      _gameRegion ! Envelope(initialize.gameId, GetStatus())
    })
    case msg@Validation(validationMessage, errorCode, e) =>
      send(msg)
    case StatusResult(id, gameMeta) =>
      _initialize.foreach(i => i.auth match {
        case Some(auth) =>
          log.debug("{{}}: Authorizing user: {}.", remote, auth.playerId)
          _gameRegion ! Envelope(i.gameId, GetPlayer(auth.playerId))
        case None =>
          if (i.mode == GameServerHandlerMode.Player) {
            send(Validation(s"Mode ${i.mode} requires auth for game ${i.gameId}.", GameEvents.RequiresAuth))
          } else {
            eventBus.subscribe(self, i.gameId)
            log.debug("{{}}: Initialized game {} in mode {} as observer.", remote, i.gameId, i.mode)
            send(GameServerHandlerResponseType.Initialized,
              Message(s"Observing game ${i.gameId}.", GameEvents.ObservingGame))
            become(observerReceive)
          }
      })
    case PlayerResult(id, player) =>
      _initialize.foreach(i => {
        i.auth.foreach(a => {
          if (player.isAuthorized(a.time, a.signature)) {
            eventBus.subscribe(self, i.gameId)
            log.debug("{{}}: Initialized game {} in mode {} as player {}.", remote, i.gameId, i.mode, a.playerId)
            _gameRegion ! Envelope(i.gameId, Join(a.playerId))
            become(playerReceive)
          } else {
            send(Validation(s"Player ${a.playerId} is not authorized for game ${i.gameId}.",
              GameEvents.PlayerNotAuthorized))
          }
        })
      })
    case PlayerJoinedResult(id, playerId, newlyJoined) =>
      log.debug("{{}}: Player {} joined game {}.", remote, playerId, id)
      newlyJoined match {
        case true =>
          send(GameServerHandlerResponseType.Initialized,
            Message(s"Joined game $id for the first time as player $playerId.", GameEvents.PlayerJoined))
        case false =>
          send(GameServerHandlerResponseType.Initialized,
            Message(s"Joined game $id again as player $playerId.", GameEvents.PlayerJoinedAgain))
      }
    case closed: ConnectionClosed => context stop self
  }

  protected def maximumLineBytes: Int = _defaultMaximumLineBytes

  private def buffer(data: ByteString, callback: (String) => Unit): Unit = {
    _buffer ++= data

    if (_buffer.size > maximumLineBytes) {
      send(Validation(s"Messages must be terminated by \\n before $maximumLineBytes bytes.",
        GameEvents.MaxBytesReached))
    } else {
      parseBuffer(callback)
    }
  }

  private def parseBuffer(callback: (String) => Unit): Unit = {
    if (_buffer.size > 1) {
      val possibleMatchPos = _buffer.indexOf('\n')
      if (possibleMatchPos == -1) {
        _nextPossibleMatch = _buffer.size
      } else {
        val parsedLine = _buffer.slice(0, possibleMatchPos).utf8String
        _buffer = _buffer.drop(possibleMatchPos + 1)
        _nextPossibleMatch -= possibleMatchPos + 1

        if (parsedLine.length > 1) {
          log.debug("{{}}: Received: {}.", remote, parsedLine)
          callback(parsedLine)
        }

        parseBuffer(callback)
      }
    }
  }

  private def sendGameState(responseType: GameServerHandlerResponseType, state: GameState,
                            responseMessage: Message) = {
    var you: Option[PlayerGamePrivateState] = None
    val others = ListBuffer[PlayerGamePublicState]()
    val shared: Option[TSharedState] = state.shared

    var position: Option[Int] = None
    var current = 1

    state.all.foreach(s => {
      if (_initialize.exists(i => i.auth.exists(a => a.playerId == s.id))) {
        you = Some(s)
        position = Some(current)
      } else {
        others += s.public
        current += 1
      }
    })

    send(GameServerHandlerResponseMessage(responseType, you = you,
      others = Some(others), shared = shared, position = position, message = Some(responseMessage)))
  }

  private def send(responseType: GameServerHandlerResponseType, message: Message): Unit = {
    send(GameServerHandlerResponseMessage(
      responseType, message = Some(message)))
  }

  private def send(e: Validation): Unit = {
    log.error(e.exception.orNull, "{{}}: ({}) {}", remote, e.errorCode, e.message)
    send(GameServerHandlerResponseMessage(GameServerHandlerResponseType.Error,
      message = Some(Message(e.message, e.errorCode))))
  }

  private def send(responseMessage: GameServerHandlerResponseMessage): Unit = {
    send(responseMessage.toJson)
  }

  private def send(json: JsValue): Unit = {
    send(json.compactPrint)
  }

  private def send(message: String): Unit = {
    log.debug("{{}}: Sending: {}", remote, message)
    connection ! Write(ByteString(message + '\n'))
  }
}