package proton.game

import java.io.{IOException, ObjectInputStream, ObjectOutputStream}
import java.util.UUID

import proton.game.GameServerHandler.GameServerHandlerResponseType.GameServerHandlerResponseType
import spray.json.{RootJsonWriter, _}

trait GameCommand extends Serializable
trait GamePublicState extends Serializable
trait GamePrivateState[TPublic <: GamePublicState] extends Serializable {
  def public: TPublic
}
trait GameSharedState extends Serializable

trait PlayerGameState[TState] {
  def id: UUID
  def name: String
  def state: TState
}

trait GameMessage[TConfig <: GameConfig, TSharedState <: GameSharedState, TPublicState <: GamePublicState,
TPrivateState <: GamePrivateState[TPublicState], TCommand <: GameCommand] extends Serializable {

  trait GameRequest
  case class GetStatus() extends GameRequest
  case class GetState() extends GameRequest
  case class GetPlayer(playerId: UUID) extends GameRequest
  case class Configure(config: TConfig) extends GameRequest
  case class Join(playerId: UUID) extends GameRequest
  case class Play(playerId: UUID, command: TCommand) extends GameRequest
  case class Start() extends GameRequest
  case class Stop() extends GameRequest
  case class Clear() extends GameRequest
  case class Transition() extends GameRequest
  case class Timeout() extends GameRequest

  trait GameEvent
  case class Configured(config: TConfig) extends GameEvent
  case class Joined(playerId: UUID) extends GameEvent
  case class Played(playerId: UUID, command: TCommand) extends GameEvent
  case class Started() extends GameEvent
  case class Stopped() extends GameEvent
  case class Cleared() extends GameEvent
  case class Transitioned() extends GameEvent
  case class TimedOut() extends GameEvent

  trait GameResponse
  case class StatusResult(id: UUID, gameMeta: GameMeta) extends GameResponse
  case class ConfiguredResult(id: UUID, gameMeta: GameMeta) extends GameResponse
  case class StateResult(id: UUID, state: Seq[PlayerGamePublicState]) extends GameResponse
  case class PlayerResult(id: UUID, player: proton.game.Player) extends GameResponse
  case class PlayerJoinedResult(id: UUID, playerId: UUID, newlyJoined: Boolean) extends GameResponse
  case class PlayedResult(id: UUID, commands: Seq[TCommand]) extends GameResponse
  case class StartedResult(id: UUID, gameMeta: GameMeta) extends GameResponse
  case class StoppedResult(id: UUID, gameMeta: GameMeta) extends GameResponse
  case class ClearedResult(id: UUID, gameMeta: GameMeta) extends GameResponse

  trait GameNotifyEvent
  case class GameNotifyJoined(player: PlayerIdentity) extends GameNotifyEvent
  case class GameNotifyStarted() extends GameNotifyEvent
  case class GameNotifyStopped() extends GameNotifyEvent
  case class GameNotifyCleared() extends GameNotifyEvent
  case class GameNotifyTransitioned(state: GameState) extends GameNotifyEvent
  case class GameNotifyTimedOut() extends GameNotifyEvent
  case class GameNotifyCompleted(state: GameState) extends GameNotifyEvent

  case class PlayerGamePublicState(id: UUID, name: String, state: TPublicState)
    extends PlayerGameState[TPublicState]

  case class PlayerGamePrivateState(id: UUID, name: String, state: TPrivateState)
    extends PlayerGameState[TPrivateState] {
    def public: PlayerGamePublicState = new PlayerGamePublicState(id, name, state.public)
  }

  case class PlayerGamePrivateStateOrdering(ordering: Ordering[TPrivateState])
    extends Ordering[PlayerGamePrivateState] {
    override def compare(x: PlayerGamePrivateState,
                         y: PlayerGamePrivateState): Int = ordering.compare(x.state, y.state)
  }

  case class GameState(shared: Option[TSharedState], all: Seq[PlayerGamePrivateState])

  case class GameServerHandlerResponseMessage(responseType: GameServerHandlerResponseType,
                                              commands: Option[Seq[TCommand]] = None,
                                              you: Option[PlayerGamePrivateState] = None,
                                              others: Option[Seq[PlayerGamePublicState]] = None,
                                              shared: Option[TSharedState] = None,
                                              position: Option[Int] = None,
                                              message: Option[Message] = None)

  object GameMessageProtocol extends GameProtocol with Serializable {
    implicit val statusResultFormat = rootFormat(jsonFormat2(StatusResult))
    implicit val configuredResultFormat = rootFormat(jsonFormat2(ConfiguredResult))

    implicit object PlayerResultWriter extends JsonWriter[PlayerResult] with Serializable {
      def write(p: PlayerResult) = JsObject(
        "id" -> p.id.toJson,
        "player" -> p.player.toJson
      )
    }

    implicit val playerJoinedResultFormat = rootFormat(jsonFormat3(PlayerJoinedResult))
    implicit val startedResultFormat = rootFormat(jsonFormat2(StartedResult))
    implicit val stoppedResultFormat = rootFormat(jsonFormat2(StoppedResult))
    implicit val clearedResultFormat = rootFormat(jsonFormat2(ClearedResult))

    implicit def playerGamePublicStateJson(implicit publicStateFormat: JsonFormat[TPublicState]) =
      rootFormat(jsonFormat3(PlayerGamePublicState))

    implicit def playerGamePrivateStateJson(implicit publicStateFormat: JsonFormat[TPublicState],
                                            privateStateFormat: JsonFormat[TPrivateState]) =
      rootFormat(jsonFormat3(PlayerGamePrivateState))

    implicit def stateResultJson(implicit publicStateFormat: JsonFormat[TPublicState]) =
      rootFormat(jsonFormat2(StateResult))

    implicit def playedResultJson(implicit commandFormat: JsonFormat[TCommand]) =
      rootFormat(jsonFormat2(PlayedResult))

    implicit def gameResponseFormat(implicit commandFormat: JsonFormat[TCommand],
                                    publicStateFormat: JsonFormat[TPublicState]) = new RootJsonWriter[GameResponse] {
      def write(r: GameResponse) = r match {
        case msg@StatusResult(id, gameMeta) => msg.toJson
        case msg@ConfiguredResult(id, gameMeta) => msg.toJson
        case msg@StateResult(id, state) => msg.toJson
        case msg@PlayerResult(id, player) => msg.toJson
        case msg@PlayerJoinedResult(id, playerId, newlyJoined) => msg.toJson
        case msg@PlayedResult(id, commands) => msg.toJson
        case msg@StartedResult(id, gameMeta) => msg.toJson
        case msg@StoppedResult(id, gameMeta) => msg.toJson
        case msg@ClearedResult(id, gameMeta) => msg.toJson
      }
    }

    implicit def gameServerHandlerResponseMessageWriter(implicit sharedStateFormat: JsonWriter[TSharedState],
                                                        publicStatesFormat: JsonWriter[Seq[PlayerGamePublicState]],
                                                        privateStateFormat: JsonWriter[PlayerGamePrivateState],
                                                        commandsFormat: JsonWriter[Seq[TCommand]]) =
      new JsonWriter[GameServerHandlerResponseMessage] {

        def write(m: GameServerHandlerResponseMessage) = {
          val fields = Seq[Option[JsField]](
            Some("responseType" -> m.responseType.toJson),
            m.commands.map(commands => "commands" -> commands.toJson),
            m.you.map(you           => "commands" -> you.toJson),
            m.others.map(others     => "others"   -> others.toJson),
            m.shared.map(shared     => "shared"   -> shared.toJson),
            m.position.map(position => "position" -> position.toJson),
            m.message.map(message   => "message"  -> message.toJson)
          )
          JsObject(fields.flatten: _*)
        }
      }

    @throws(classOf[IOException])
    private def writeObject(out: ObjectOutputStream): Unit = {}

    @throws(classOf[IOException])
    private def readObject(in: ObjectInputStream): Unit = {}
  }
}