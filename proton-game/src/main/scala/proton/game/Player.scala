package proton.game

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Clock, LocalDateTime}
import java.util.{Base64, UUID}
import javax.crypto.spec.SecretKeySpec
import javax.crypto.{KeyGenerator, Mac}

@SerialVersionUID(1L)
class Player(val id: UUID, val name: String, val secret: String) extends Serializable {
  override def hashCode = id.hashCode()

  override def equals(other: Any) = other match {
    case that: Player => this.id == that.id
    case _ => false
  }

  override def toString = s"$name ($id)"

  def identity = PlayerIdentity(id, name)

  def isAuthorized(time: LocalDateTime, signature: String): Boolean = {
    val seconds = ChronoUnit.SECONDS.between(time, LocalDateTime.now(Clock.systemUTC()))

    if (seconds < -300 || seconds > 300) {
      false
    } else {
      val secretKeySpec = new SecretKeySpec(secret.getBytes, "HmacSHA256")
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(secretKeySpec)

      val message = id.toString.toLowerCase + "|" + time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      val hmac = mac.doFinal(message.getBytes("UTF-8"))

      val encoded = Base64.getEncoder.encodeToString(hmac)

      encoded.equalsIgnoreCase(signature)
    }
  }
}

object Player {
  def apply(name: String) = new Player(UUID.randomUUID(), name, generateKey)

  def apply(id: UUID, name: String) = new Player(id, name, generateKey)

  private def generateKey: String = {
    val keyGenerator: KeyGenerator = KeyGenerator.getInstance("HmacSHA256")
    Base64.getEncoder.encodeToString(keyGenerator.generateKey().getEncoded)
  }

  def apply(id: UUID, name: String, secret: String) = new Player(id, name, secret)

  def apply(identity: PlayerIdentity) = new Player(identity.id, identity.name, generateKey)

  def apply(identity: PlayerIdentity, secret: String) = new Player(identity.id, identity.name, secret)
}

case class PlayerIdentity(id: UUID, name: String)