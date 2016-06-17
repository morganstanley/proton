package proton.game

import java.time.LocalDateTime

import proton.game.GameStatus.GameStatus

object GameStatus extends Enumeration {
  type GameStatus = Value
  val GameNotConfigured, GameConfigured, GameReady, GameRunning, GameStopped, GameCompleted = Value
}

case class GameMeta(status: GameStatus, connectionString: String, joined: Option[Seq[PlayerIdentity]] = None,
                    unjoined: Option[Seq[PlayerIdentity]] = None, startTime: Option[LocalDateTime] = None)