package proton.game.hermes

import java.time.LocalDateTime
import java.util.UUID

import proton.game._

import scala.concurrent.duration.{FiniteDuration, _}

class HermesGameTickerConfig(val gameFile: UUID, override val players: Seq[Player],
                             override val startTime: Option[LocalDateTime],
                             override val tickDuration: FiniteDuration,
                             override val timeoutDuration: Option[FiniteDuration]) extends GameTickerConfig {
  override val minPlayers: Option[Int] = Some(1)
  override val maxPlayers: Option[Int] = None
}

case class HermesGameTickerConfigFactory(gameFile: UUID, players: Seq[PlayerIdentity], startTime: Option[LocalDateTime],
                                         tickDuration: Option[FiniteDuration], timeoutDuration: Option[FiniteDuration])
  extends GameTickerConfigFactory[HermesGameTickerConfig] {

  override def build(): HermesGameTickerConfig = {
    new HermesGameTickerConfig(gameFile, players.map(p => Player(p)), startTime,
      tickDuration.getOrElse(200.milliseconds), timeoutDuration)
  }
}