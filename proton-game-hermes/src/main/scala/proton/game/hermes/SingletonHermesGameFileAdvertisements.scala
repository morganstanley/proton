package proton.game.hermes


import java.time.LocalDateTime
import java.util.UUID

import akka.actor.ActorLogging
import akka.cluster.Cluster
import akka.cluster.ddata.{DistributedData, ORSet, ORSetKey}
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import akka.persistence.PersistentActor
import proton.game.Validation

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class HermesGameFileAdvertisement(name: String)(val id: UUID, val created: LocalDateTime,
                                                     val visible: Option[Boolean] = None) {
  def this(advertisement: HermesGameFileAdvertisement, visible: Boolean) =
    this(advertisement.name)(advertisement.id, advertisement.created, Some(visible))
}

object SingletonHermesGameFileAdvertisements {
  trait AdvertisementMessage
  case class AddAdvertisement(name: String, id: UUID) extends AdvertisementMessage
  case class SetAdvertisementVisibility(name: String, visible: Boolean) extends AdvertisementMessage
  case class ChangeAdvertisementName(from: String, to: String) extends AdvertisementMessage
  case class RemoveAdvertisement(name: String) extends AdvertisementMessage
  case class GetAdvertisementByName(name: String) extends AdvertisementMessage
  case class GetAdvertisementById(id: UUID) extends AdvertisementMessage
  case class GetAllAdvertisements() extends AdvertisementMessage

  trait AdvertisementEvent
  case class AdvertisementAdded(advertisement: HermesGameFileAdvertisement) extends AdvertisementEvent
  case class AdvertisementShown(advertisement: HermesGameFileAdvertisement) extends AdvertisementEvent
  case class AdvertisementHidden(advertisement: HermesGameFileAdvertisement) extends AdvertisementEvent
  case class AdvertisementNameChanged(from: HermesGameFileAdvertisement, to: HermesGameFileAdvertisement)
    extends AdvertisementEvent
  case class AdvertisementRemoved(advertisement: HermesGameFileAdvertisement) extends AdvertisementEvent

  trait AdvertisementResponse
  case class AdvertisementAddedResult(advertisement: HermesGameFileAdvertisement, added: Boolean)
    extends AdvertisementResponse
  case class AdvertisementVisibilitySetResult(advertisement: HermesGameFileAdvertisement, updated: Boolean)
    extends AdvertisementResponse
  case class AdvertisementChangeNameResult(advertisement: HermesGameFileAdvertisement) extends AdvertisementResponse
  case class AdvertisementRemoveResult(removed: Boolean) extends AdvertisementResponse
  case class GetAdvertisementResult(advertisement: HermesGameFileAdvertisement) extends AdvertisementResponse
  case class GetAdvertisementsResult(advertisements: Seq[HermesGameFileAdvertisement]) extends AdvertisementResponse

  val gameFileAdvertisementsName = "gameFileAdvertisements"
  val mapKey = ORSetKey[HermesGameFileAdvertisement]("gameFileAdvertisementsMap")
}

class SingletonHermesGameFileAdvertisements(settings: HermesGameTickerModuleSettings)
  extends PersistentActor with ActorLogging {
  import SingletonHermesGameFileAdvertisements._
  import ChunkedHermesGameFiles._
  import akka.cluster.ddata.Replicator._

  private val _visible = mutable.Set[String]()
  private val _all = mutable.Map[String, HermesGameFileAdvertisement]()

  private val _replicator = DistributedData(context.system).replicator
  private implicit val _cluster = Cluster(context.system)

  private val _gameFiles = ClusterSharding(context.system).shardRegion(gameFilesRegionName)

  override def receiveRecover: Receive = {
    case event: AdvertisementEvent => updateState(event)
  }

  def updateState(e: AdvertisementEvent) = e match {
    case AdvertisementAdded(advertisement) => _all += (advertisement.name.toLowerCase() -> advertisement)
    case AdvertisementShown(advertisement) =>
      _visible += advertisement.name
      _replicator ! Update(mapKey, ORSet.empty[HermesGameFileAdvertisement],
        WriteAll(settings.namesDDataTimeout))(_ + advertisement)
    case AdvertisementHidden(advertisement) =>
      _visible -= advertisement.name
      _replicator ! Update(mapKey, ORSet.empty[HermesGameFileAdvertisement],
        WriteAll(settings.namesDDataTimeout))(_ - advertisement)
    case AdvertisementNameChanged(from, to) =>
      val fromName = from.name.toLowerCase()
      val toName = to.name.toLowerCase()

      _all -= fromName
      _all += (toName -> to)

      if (_visible.contains(fromName)) {
        _visible -= fromName
        _visible += toName

        _replicator ! Update(mapKey, ORSet.empty[HermesGameFileAdvertisement],
          WriteAll(settings.namesDDataTimeout))(_ - from)
        _replicator ! Update(mapKey, ORSet.empty[HermesGameFileAdvertisement],
          WriteAll(settings.namesDDataTimeout))(_ + to)
      }
    case AdvertisementRemoved(advertisement) =>
      val name = advertisement.name.toLowerCase()

      if (_visible.contains(name)) {
        _visible -= name
        _replicator ! Update(mapKey, ORSet.empty[HermesGameFileAdvertisement],
          WriteAll(settings.namesDDataTimeout))(_ - advertisement)
      }

      _all -= name
  }

  override def receiveCommand: Receive = LoggingReceive {
    case AddAdvertisement(name, id) =>
      val lowerName = name.toLowerCase()
      _all.get(lowerName) match {
        case Some(advertisement) =>
          if (advertisement.id == id) {
            sender ! AdvertisementAddedResult(advertisement, added = false)
          } else {
            sender ! Validation(s"A game file advertisement with name '$lowerName' and id $id already exists.",
              HermesGameEvents.AdvertisementAlreadyExists)
          }
        case None => persist(AdvertisementAdded(HermesGameFileAdvertisement(name)(id, LocalDateTime.now())))(e => {
          updateState(e)
          sender ! AdvertisementAddedResult(e.advertisement, added = true)
        })
      }
    case SetAdvertisementVisibility(name, visible) =>
      val lowerName = name.toLowerCase()
      _all.get(lowerName) match {
        case Some(advertisement) =>
          val current = _visible.contains(advertisement.name)
          if (current != visible) {
            persist(visible match {
              case true => AdvertisementShown(advertisement)
              case false => AdvertisementHidden(advertisement)
            })(e => {
              updateState(e)
              sender ! AdvertisementVisibilitySetResult(new HermesGameFileAdvertisement(advertisement, !current),
                updated = true)
            })
          } else {
            sender ! AdvertisementVisibilitySetResult(new HermesGameFileAdvertisement(advertisement, current),
              updated = false)
          }
        case None => sender ! Validation(s"The game file advertisement with name $lowerName does not exist.",
          HermesGameEvents.AdvertisementDoesNotExist)
      }
    case ChangeAdvertisementName(from, to) =>
      val lowerName = from.toLowerCase()
      _all.get(lowerName) match {
        case Some(advertisement) =>
          persist(
            AdvertisementNameChanged(
              advertisement, HermesGameFileAdvertisement(to)(advertisement.id, advertisement.created)))(e => {

            updateState(e)
            sender ! AdvertisementChangeNameResult(e.to)
          })
        case None => sender ! Validation(s"The game file advertisement with name $lowerName does not exist.",
          HermesGameEvents.AdvertisementDoesNotExist)
      }
    case RemoveAdvertisement(name) =>
      val lowerName = name.toLowerCase()
      _all.get(lowerName) match {
        case Some(advertisement) =>
          persist(AdvertisementRemoved(advertisement))(e => {
            updateState(e)
            sender ! AdvertisementRemoveResult(true)
          })
        case None =>
          sender ! AdvertisementRemoveResult(false)
      }
    case GetAdvertisementByName(name) =>
      val lowerName = name.toLowerCase()
      _all.get(lowerName) match {
        case Some(advertisement) => sender ! GetAdvertisementResult(advertisement)
        case None => sender ! Validation(s"The game file advertisement with name $lowerName does not exist.",
          HermesGameEvents.AdvertisementDoesNotExist)
      }
    case GetAdvertisementById(id) =>
      _all.find(x => x._2.id == id) match {
        case Some(advertisement) => sender ! GetAdvertisementResult(advertisement._2)
        case None => sender ! Validation(s"The game file advertisement with id $id does not exist.",
          HermesGameEvents.AdvertisementDoesNotExist)
      }
    case GetAllAdvertisements() =>
      val advertisements = new ListBuffer[HermesGameFileAdvertisement]()
      _all.foreach { case (key, value) => advertisements +=
        new HermesGameFileAdvertisement(value, _visible.contains(value.name.toLowerCase))
      }
      sender ! GetAdvertisementsResult(advertisements)
  }

  override def persistenceId = gameFileAdvertisementsName
}
