package proton.game

import java.time.LocalDateTime

import scala.concurrent.duration.FiniteDuration

trait GameConfig extends Serializable {
  def players: Seq[Player]

  def startTime: Option[LocalDateTime]

  def minPlayers: Option[Int]

  def maxPlayers: Option[Int]
}

trait GameTickerConfig extends GameConfig {
  def tickDuration: FiniteDuration

  def timeoutDuration: Option[FiniteDuration]
}

trait GameConfigFactory[TConfig <: GameConfig] {
  def build(): TConfig
}

trait GameTickerConfigFactory[TConfig <: GameTickerConfig] {
  def build(): TConfig
}