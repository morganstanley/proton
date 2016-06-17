package proton.game

import java.net.InetSocketAddress

import akka.actor._
import akka.io.Tcp.Bind
import akka.io.{IO, Tcp}

class GameServer(handlerProps: (ActorRef, InetSocketAddress) => Props)
  extends Actor with ActorLogging {

  import Tcp._

  override def receive: Receive = {
    case b@Bound(localAddress) => log.debug("Bound server to {}.", localAddress)
    case c@CommandFailed(cmd) =>
      log.error("Command {} failed so closing server.", cmd)
      context stop self
    case c@Connected(remote, local) =>
      val connection = sender()
      val handler = context.actorOf(handlerProps(connection, remote))
      connection ! Register(handler)
  }
}

class GameServerFactory(host: String, port: Int, handlerProps: (ActorRef, InetSocketAddress) => Props)
                       (implicit system: ActorSystem) extends GameEndPoint {
  override val connectionString: String = host + ":" + port

  val server = system.actorOf(Props(classOf[GameServer], handlerProps))

  override def init(): Unit = {
    IO(Tcp) ! Bind(server, new InetSocketAddress(host, port))
  }
}