package proton.game.hermes

import java.time.LocalDateTime
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.cluster.singleton._
import akka.http.scaladsl.model.MediaTypes
import akka.http.scaladsl.server.{Route, RouteResult}
import akka.http.scaladsl.unmarshalling._
import kantan.codecs.Result.Success
import proton.game._
import akka.http.scaladsl.server.Directives._
import HermesGameProtocol._
import spray.json.JsonPrinter

import scala.concurrent.Promise

object HermesGameTickerModule {
  case class AdvertisementDefinition(name: String, id: Option[UUID] = None)
  case class AdvertisementNameChange(name: String)
  case class AdvertisementVisibilityChange(visible: Boolean)
  case class GameFileCompleteChange(complete: Boolean)
}

class HermesGameTickerModule(system: ActorSystem, settings: HermesGameTickerModuleSettings,
                             gameFileRepository: HermesGameFileRepository, eventBus: GameEventBus)
  extends GameTickerModule[HermesGameTickerConfig, HermesSharedState, HermesGamePublicState, HermesGamePrivateState,
    HermesGameCommand, HermesGameTicker, HermesGameTickerConfigFactory](system, HermesGameMessage, settings, eventBus) {

  import MediaTypes._
  import kantan.csv._
  import kantan.csv.ops._
  import ChunkedHermesGameFiles._
  import ChunkedHermesGameFileEntries._
  import HermesGameTickerModule._
  import SingletonHermesGameFileAdvertisements._
  import HermesGameTickerModulePerRequest._

  override def name: String = "hermes"

  lazy val gameFileEntries: ActorRef = ClusterSharding(system).start(
    typeName = gameFileEntriesRegionName,
    entityProps = Props(classOf[ChunkedHermesGameFileEntries], settings),
    settings = ClusterShardingSettings(system),
    extractEntityId = EnvelopeEntity.extractEntityId,
    extractShardId = EnvelopeEntity.extractShardId(settings.numberOfShards))

  lazy val gameFiles: ActorRef = ClusterSharding(system).start(
    typeName = gameFilesRegionName,
    entityProps = Props(classOf[ChunkedHermesGameFiles], settings),
    settings = ClusterShardingSettings(system),
    extractEntityId = EnvelopeEntity.extractEntityId,
    extractShardId = EnvelopeEntity.extractShardId(settings.numberOfShards))

  lazy val gameFileAdvertisements = system.actorOf(ClusterSingletonProxy.props(
    singletonManagerPath = "/user/" + gameFileAdvertisementsName,
    settings = ClusterSingletonProxySettings(system)),
    name = gameFileAdvertisementsName + "Proxy")

  val replicator = DistributedData(system).replicator
  implicit val cluster = Cluster(system)

  implicit val yearDecoder: CellDecoder[LocalDateTime] = CellDecoder[String].map(date ⇒ LocalDateTime.parse(date))

  private val csvGameFileUnmarshaller = {
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(`text/csv`)
      .mapWithCharset { (data, charset) =>
        val csv = data.decodeString(charset.nioCharset.name)
        val entries = csv.asCsvReader[(LocalDateTime, Int, Int, Int)](',', header = false).map {
          case Success((time, euResult, naResult, apResult)) =>
            val regionData = Map(
              HermesGameRegion.EU -> euResult,
              HermesGameRegion.NA -> naResult,
              HermesGameRegion.AP -> apResult
            )
            Some(new HermesGameFileEntry(time, regionData))
          case _ =>
            None
        }
        entries.flatten.toSeq
      }
  }

  implicit val AddGameFileEntriesUnmarshaller = Unmarshaller.firstOf(
    sprayJsonUnmarshaller[Seq[HermesGameFileEntry]], csvGameFileUnmarshaller)

  override def decorateRoute(route: Route): Route =
    route ~ path(name / "file") {
      (get & parameters('visible.as[Boolean] ! true)) {
        parameters('name.?) { name =>
          hermesPreRequest(SearchVisibleFilesRequest(name, replicator))
        }
      } ~ get {
        parameters('name.?) { name =>
          hermesPreRequest(SearchFilesRequest(name, gameFileAdvertisements))
        }
      } ~ post {
        entity(as[AdvertisementDefinition]) { advertisement =>
          hermesPreRequest(CreateAdvertisementRequest(advertisement.name, advertisement.id, gameFiles,
            gameFileAdvertisements))
        }
      }
    } ~ path(name / "file" / JavaUUID) { id =>
      get {
        hermesPreRequest(GetFileRequestById(id, gameFiles, gameFileAdvertisements))
      } ~ delete {
        hermesPreRequest(DeleteFileRequest(id, gameFileAdvertisements))
      }
    } ~ path(name / "file" / JavaUUID / "name") { id =>
      post {
        entity(as[AdvertisementNameChange]) { nameChange =>
          hermesPreRequest(ChangeFileNameRequest(id, nameChange.name, gameFileAdvertisements))
        }
      }
    } ~ path(name / "file" / JavaUUID / "visible") { id =>
      post {
        entity(as[AdvertisementVisibilityChange]) { visibilityChange =>
          hermesPreRequest(SetFileVisibilityRequest(id, visibilityChange.visible, gameFiles,
            gameFileAdvertisements))
        }
      }
    }~ path(name / "file" / JavaUUID / "settings") { id =>
      post {
        entity(as[HermesGameFileSettings]) { settings =>
          hermesPreRequest(ChangeFileSettingsRequest(id, settings, gameFiles, gameFileAdvertisements))
        }
      }
    } ~ path(name / "file" / JavaUUID / "complete") { id =>
      post {
        entity(as[GameFileCompleteChange]) { completeChange =>
          hermesPreRequest(SetFileCompleteRequest(id, completeChange.complete, gameFiles,
            gameFileAdvertisements))
        }
      }
    } ~ path(name / "file" / JavaUUID / "entries") { id =>
      post {
        entity(as[Seq[HermesGameFileEntry]]) { entries =>
          hermesPreRequest(AddFileEntriesRequest(id, entries, gameFiles, gameFileEntries, gameFileAdvertisements))
        }
      }
    } ~ path(name / "file" / JavaUUID / "entries" / JavaUUID) { (id, entryId) =>
      get {
        hermesPreRequest(GetFileEntriesRequestById(id, entryId, gameFiles, gameFileEntries,
          gameFileAdvertisements))
      }
    }

  def hermesPreRequest(msg: HermesGameTickerModulePerRequestMessage): Route =
    httpRequest => {
      val p = Promise[RouteResult]()
      system.actorOf(Props(classOf[HermesGameTickerModulePerRequest], settings, httpRequest.request, p, msg))
      p.future
    }

  override def getEndpoint: GameEndPoint = new GameServerFactory(settings.host, settings.port,
    (connection, remote) => Props(classOf[HermesGameServerHandler], this, eventBus, connection, remote))(system)

  override protected def additionalTickerPropArgs = Seq(gameFileRepository, settings)

  override def init() = {
    super.init()
    gameFileEntries
    gameFiles

    system.actorOf(ClusterSingletonManager.props(
      singletonProps = Props(classOf[SingletonHermesGameFileAdvertisements], settings),
      terminationMessage = PoisonPill,
      settings = ClusterSingletonManagerSettings(system)),
      name = gameFileAdvertisementsName)
  }
}