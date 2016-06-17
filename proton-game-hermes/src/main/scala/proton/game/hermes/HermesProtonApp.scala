package proton.game.hermes

import com.typesafe.config.Config
import proton.game._
import scaldi.Module

class HermesProtonModule(config: Config) extends Module {
  bind[HermesGameTickerModuleSettings] to new HermesGameTickerModuleSettings(
    inject[GameTickerModuleSettings]('base),
    config.getInt("proton.game.hermes.port"),
    config.getInt("proton.game.hermes.file.chunked.entries"),
    config.getDuration("proton.game.hermes.game.timeout"),
    config.getDuration("proton.game.hermes.names.ddata.timeout"),
    config.getDuration("proton.game.hermes.file.chunked.timeout"),
    config.getDuration("proton.game.hermes.file.chunked.append.timeout"),
    config.getDuration("proton.game.hermes.file.chunked.repo.timeout"))

  bind[HermesGameFileRepository] to injected[ChunkedHermesGameFileRepository]

  bind[GameModule] to injected[HermesGameTickerModule]
}

object HermesProtonApp extends ProtonApp {
  init("hermes-dev.conf", config => new HermesProtonModule(config))
}
