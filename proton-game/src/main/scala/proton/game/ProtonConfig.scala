package proton.game

import java.net.NetworkInterface

import com.typesafe.config._

import scala.collection.JavaConversions._

case class ProtonConfig(file: String) {

  import ConfigFactory._

  lazy val config = asConfig()

  private def asConfig(): Config = {
    val config = load(
      getClass.getClassLoader,
      file,
      ConfigParseOptions.defaults,
      ConfigResolveOptions.defaults.setAllowUnresolved(true)
    )

    val ip = if (config hasPath "proton.ip") config getString "proton.ip" else getIpAddress getOrElse "127.0.0.1"
    ConfigFactory.empty
      .withValue("proton.ip", ConfigValueFactory fromAnyRef ip)
      .withFallback(config)
      .resolve
  }

  def getIpAddress: Option[String] = {
    val interfaces = NetworkInterface.getNetworkInterfaces
    val inetAddresses = interfaces.flatMap(interface => interface.getInetAddresses)

    inetAddresses.find(_.isSiteLocalAddress).map(_.getHostAddress)
  }
}

object ProtonConfig {

  def parse(moduleName: String, args: Seq[String]): Option[ProtonConfig] = {

    val parser = new scopt.OptionParser[ProtonConfig]("proton-game") {
      head("proton-game-" + moduleName, "1.x")
      opt[String]("file") optional() action { (fileParam, c) =>
        c.copy(file = fileParam)
      } text "specifies the config file to use"
    }

    parser.parse(args, ProtonConfig(moduleName))
  }
}