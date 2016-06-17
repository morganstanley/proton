package proton.game.hermes

import java.time.Duration
import java.util.concurrent.TimeUnit

import proton.game.GameTickerModuleSettings

import scala.concurrent.duration.FiniteDuration

class HermesGameTickerModuleSettings(baseSettings: GameTickerModuleSettings,
                                     val port: Int,
                                     val chunkSize: Int,
                                     val gameTimeoutDuration: Duration,
                                     val namesDDataTimeoutDuration: Duration,
                                     val chunkedTimeoutDuration: Duration,
                                     val chunkedAppendTimeoutDuration: Duration,
                                     val chunkedRepositoryTimeoutDuration: Duration)
  extends GameTickerModuleSettings(baseSettings) {
  val namesDDataTimeout = FiniteDuration(namesDDataTimeoutDuration.toNanos, TimeUnit.NANOSECONDS)
  val gameTimeout = FiniteDuration(gameTimeoutDuration.toNanos, TimeUnit.NANOSECONDS)
  val chunkedTimeout = FiniteDuration(chunkedTimeoutDuration.toNanos, TimeUnit.NANOSECONDS)
  val chunkedAppendTimeout = FiniteDuration(chunkedAppendTimeoutDuration.toNanos, TimeUnit.NANOSECONDS)
  val chunkedRepositoryTimeout = FiniteDuration(chunkedRepositoryTimeoutDuration.toNanos, TimeUnit.NANOSECONDS)
}
