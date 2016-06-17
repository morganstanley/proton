package proton.game

import java.time.LocalDateTime
import java.util.UUID

import spray.json._
import akka.actor.ActorSystem

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Success

sealed trait MockGameCommand extends GameCommand
case class AddToTest(amount: Int) extends MockGameCommand

class MockGameTickerConfig(override val players: Seq[Player]) extends GameTickerConfig {
  override val tickDuration: FiniteDuration = 1.second

  override val timeoutDuration: Option[FiniteDuration] = Some(1.minute)

  override val startTime: Option[LocalDateTime] = None

  override val minPlayers: Option[Int] = Some(1)

  override val maxPlayers: Option[Int] = Some(10)
}

case class MockGameTickerConfigFactory(playersIdentities: Seq[PlayerIdentity])
  extends GameTickerConfigFactory[MockGameTickerConfig] {

  override def build(): MockGameTickerConfig = new MockGameTickerConfig(playersIdentities.map(p => Player(p)))
}

case class MockGameState(test: Int) extends GamePrivateState[MockGameState] with GamePublicState {

  def this() {
    this(0)
  }

  override def public: MockGameState = this
}

case class MockGameSharedState() extends GameSharedState

object MockGameProtocol extends GameProtocol {
  implicit val addToTestFormat = rootFormat(jsonFormat1(AddToTest))
  implicit val mockGameTickerConfigFactoryFormat = rootFormat(jsonFormat1(MockGameTickerConfigFactory))
  implicit val mockGameStateFormat = rootFormat(jsonFormat1(MockGameState))

  implicit def mockGameCommandFormat = new RootJsonFormat[MockGameCommand] {
    def write(obj: MockGameCommand) = obj match {
      case command: AddToTest => command.toJson
      case _ => serializationError("Could not serialize $obj, no conversion found")
    }

    def read(json: JsValue) = json.convertTo[AddToTest]
  }
}

import MockGameProtocol._

object MockGameStateOrdering extends Ordering[MockGameState] {
  override def compare(x: MockGameState, y: MockGameState): Int = x.test - y.test
}

object MockGameEndPoint extends GameEndPoint {
  override def connectionString: String = "localhost"

  override def init() = {}
}

object MockGameMessage extends GameMessage[MockGameTickerConfig, MockGameSharedState, MockGameState,
  MockGameState, MockGameCommand]

class MockGameModule(system: ActorSystem, eventBus: GameEventBus, settings: GameTickerModuleSettings)
  extends GameTickerModule[MockGameTickerConfig, MockGameSharedState, MockGameState, MockGameState, MockGameCommand,
    MockGameTicker, MockGameTickerConfigFactory](system, MockGameMessage, settings, eventBus) {

  override def name: String = "mock"

  override def getEndpoint: GameEndPoint = MockGameEndPoint
}

class MockGameTicker(eventBus: GameEventBus)
  extends GameTicker[MockGameTickerConfig, MockGameSharedState, MockGameState, MockGameState, MockGameCommand](
    MockGameMessage, MockGameEndPoint, eventBus) {

  private var tickNumber = 0

  override protected def continue: Boolean = tickNumber < 3

  override protected def stateOrdering: Ordering[MockGameState] = MockGameStateOrdering

  protected override def initialPlayerPrivateState(playerId: UUID): Option[MockGameState] = Some(new MockGameState())

  override protected def initialSharedState(config: MockGameTickerConfig): Option[MockGameSharedState] =
    Some(MockGameSharedState())

  override protected def validateCommand(playerId: UUID, state: MockGameState, command: MockGameCommand) =
    Success(command)

  override protected def play(state: (Option[MockGameSharedState], Map[UUID, (MockGameState, Seq[MockGameCommand])])) = {
    tickNumber += 1
    Some((state._1, state._2.map(kv => (kv._1, new MockGameState(kv._2._1.test)))))
  }
}
