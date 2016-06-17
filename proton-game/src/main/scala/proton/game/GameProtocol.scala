package proton.game

import java.io.{PrintWriter, StringWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

import spray.json._
import proton.game.GameServerHandler.GameServerHandlerMode.GameServerHandlerMode
import proton.game.GameServerHandler._
import proton.game.GameServerHandler.GameServerHandlerResponseType.GameServerHandlerResponseType
import proton.game.GameStatus.GameStatus
import proton.game.GameTickerModule._

import scala.concurrent.duration.FiniteDuration

trait GameProtocol extends DefaultJsonProtocol {

  implicit val protonAppMetaFormat = rootFormat(jsonFormat2(ProtonAppMeta))

  private def getStackTrace(t: Throwable) = {
    val sw: StringWriter = new StringWriter()
    val pw: PrintWriter = new PrintWriter(sw)
    t.printStackTrace(pw)
    sw.toString
  }

  implicit object ThrowableWriter extends RootJsonWriter[Throwable] {
    def write(t: Throwable) = JsObject(
      "message" -> JsString(t.getMessage),
      "cause" -> t.getCause.toJson,
      "stackTrace" -> JsString(getStackTrace(t))
    )
  }

  implicit object MessageFormat extends RootJsonWriter[Message] {
    def write(m: Message) = JsObject(
      "summary" -> JsString(m.summary),
      "errorCode" -> JsNumber(m.errorCode)
    )
  }

  implicit object ValidationFormat extends RootJsonWriter[Validation] {
    def write(v: Validation) = {
      val fields = Seq[Option[JsField]](
        Some("message"   -> JsString(v.message)),
        Some("errorCode" -> JsNumber(v.errorCode)),
        v.exception.map(exception => "exception" -> exception.toJson)
      )
      JsObject(fields.flatten: _*)
    }
  }

  implicit object UUIDFormat extends JsonFormat[UUID] {
    def write(uuid: UUID) = JsString(uuid.toString)
    def read(value: JsValue) = value match {
      case JsString(uuid) => UUID.fromString(uuid)
      case _ => deserializationError("UUID expected.")
    }
  }

  implicit object LocalDateTimeFormat extends JsonFormat[LocalDateTime] {
    def write(dateTime: LocalDateTime) = JsString(dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
    def read(value: JsValue) = value match {
      case JsString(dateTime) => LocalDateTime.parse(dateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      case _ => deserializationError("LocalDateTime expected.")
    }
  }

  implicit object GameStatusFormat extends RootJsonFormat[GameStatus] {
    def write(obj: GameStatus): JsValue = JsString(obj.toString)
    def read(json: JsValue): GameStatus = json match {
      case JsString(str) => GameStatus.withName(str)
      case _ => throw new DeserializationException("GameStatus expected.")
    }
  }

  implicit object GameServerHandlerResponseTypeFormat extends RootJsonFormat[GameServerHandlerResponseType] {
    def write(obj: GameServerHandlerResponseType): JsValue = JsString(obj.toString)
    def read(json: JsValue): GameServerHandlerResponseType = json match {
      case JsString(str) => GameServerHandlerResponseType.withName(str)
      case _ => throw new DeserializationException("GameServerHandlerResponseType expected.")
    }
  }

  implicit object GameServerHandlerModeFormat extends RootJsonFormat[GameServerHandlerMode] {
    def write(obj: GameServerHandlerMode): JsValue = JsString(obj.toString)
    def read(json: JsValue): GameServerHandlerMode = json match {
      case JsString(str) => GameServerHandlerMode.withName(str)
      case _ => throw new DeserializationException("GameServerHandlerMode expected.")
    }
  }

  implicit val playerAuthFormat = rootFormat(jsonFormat3(PlayerAuth))
  implicit val initializeFormat = rootFormat(jsonFormat3(Initialize))
  implicit val gameTickerMessageNameFormat = rootFormat(jsonFormat1(GameTickerMessageName))
  implicit val playerIdentityFormat = rootFormat(jsonFormat2(PlayerIdentity))
  implicit val gameMetaFormat = rootFormat(jsonFormat5(GameMeta))

  implicit object PlayerWriter extends JsonWriter[Player] {
    def write(p: Player) = JsObject(
      "id" -> p.id.toJson,
      "name" -> JsString(p.name),
      "secret" -> JsString(p.secret)
    )
  }

  implicit object finiteDurationFormat extends RootJsonFormat[FiniteDuration] {
    def write(fd: FiniteDuration) = JsObject(
      "length" -> JsNumber(fd.length),
      "unit"   -> JsString(fd.unit.name())
    )

    def read(value: JsValue) = {
      value.asJsObject.getFields("length", "unit") match {
        case Seq(JsNumber(length), JsString(unit)) =>
          FiniteDuration(length.toLong, unit)
        case _ => throw new DeserializationException("FiniteDuration expected")
      }
    }
  }
}