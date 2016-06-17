package proton.game

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import org.scalatest._

import scala.concurrent.duration.DurationInt

object GameTickerSpec {
  val config =
    """
    akka {
      loggers = ["akka.testkit.TestEventListener"]
      loglevel = "DEBUG"

      actor {
        provider = "akka.cluster.ClusterActorRefProvider"
      }

      extensions = ["akka.cluster.pubsub.DistributedPubSub"]

      persistence {
        journal.plugin = "inmemory-journal"
        snapshot-store.plugin = "inmemory-snapshot-store"
      }
    }
    """
}

class GameTickerSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers
  with BeforeAndAfterAll {

  import GameStatus._
  import MockGameMessage._

  def this() = this(ActorSystem("GameTickerTest",
    ConfigFactory.parseString(GameTickerSpec.config)))

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A game ticker" should {
    "run a complete mock game" in {
      implicit val timeout = akka.util.Timeout(5.seconds)
      val gameId = UUID.randomUUID()
      val eventBus = new GameEventBus
      val actorRef = system.actorOf(Props(classOf[MockGameTicker], eventBus), gameId.toString)
      val player1 = Player("player 1")
      val player2 = Player("player 2")

      eventBus.subscribe(testActor, gameId)

      actorRef ! GetStatus()
      expectMsgPF() {
        case StatusResult(id, gameMeta) => gameMeta.status == GameNotConfigured
      }

      actorRef ! Configure(new MockGameTickerConfig(Seq(player1, player2)))
      expectMsgPF() {
        case ConfiguredResult(id, gameMeta) =>
          gameMeta.status == GameConfigured && gameMeta.unjoined.map(x => x.size).getOrElse(0) == 2
      }

      actorRef ! Join(player1.id)
      expectMsgPF() {
        case PlayerJoinedResult(id, playerId, newlyJoined) => newlyJoined
      }
      expectMsg(GameNotifyJoined(player1.identity))

      actorRef ! Join(player2.id)
      expectMsgPF() {
        case PlayerJoinedResult(id, playerId, newlyJoined) => newlyJoined
      }
      expectMsg(GameNotifyJoined(player2.identity))

      expectMsg(GameNotifyStarted())
      expectMsgType[GameNotifyTransitioned](timeout.duration)

      actorRef ! Play(player1.id, AddToTest(12))
      expectMsgPF() {
        case PlayedResult(id, commands) => commands.size == 1
      }

      expectMsgType[GameNotifyTransitioned](timeout.duration)
      expectMsgType[GameNotifyCompleted](timeout.duration)
    }
  }
}