package proton.users

import java.io.{PrintWriter, StringWriter}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

import spray.json.{JsValue, JsonFormat, _}

trait UsersProtocol {
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
}
