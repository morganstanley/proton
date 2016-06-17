package proton.game.hermes

import java.net.InetSocketAddress

import akka.actor.ActorRef
import proton.game.{GameEventBus, GameServerHandler}
import HermesGameProtocol._

class HermesGameServerHandler(gameModule: HermesGameTickerModule, eventBus: GameEventBus, connection: ActorRef,
                              remote: InetSocketAddress)
  extends GameServerHandler[HermesGameTickerConfig, HermesSharedState, HermesGamePublicState, HermesGamePrivateState,
    HermesGameCommand](gameModule, eventBus, HermesGameMessage, connection, remote)