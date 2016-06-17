package proton.game

import java.time.Duration
import java.util.concurrent.TimeUnit

import spray.json.{CompactPrinter, JsonPrinter}

import scala.concurrent.duration.FiniteDuration

class GameTickerModuleSettings(val host: String, val numberOfShards: Int, val httpTimeoutDuration: Duration,
                               val idGenerator: GameIdGenerator, val jsonPrinter: JsonPrinter) {
  def this(settings: GameTickerModuleSettings) = this(settings.host, settings.numberOfShards,
    settings.httpTimeoutDuration, settings.idGenerator, settings.jsonPrinter)
  val httpTimeout = FiniteDuration(httpTimeoutDuration.toNanos, TimeUnit.NANOSECONDS)
}