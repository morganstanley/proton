package proton.game

import java.security.SecureRandom
import java.util.UUID

trait GameIdGenerator {
  def generate(moduleName: String): UUID
}

class NameHashedGameIdGenerator(clusterName: String) extends GameIdGenerator{
  override def generate(moduleName: String): UUID = {
    var mostSigBits = asUnsigned(clusterName.hashCode.toLong) << 32
    mostSigBits |= asUnsigned(moduleName.hashCode.toLong)

    val numberGenerator: SecureRandom = new SecureRandom()
    numberGenerator.setSeed(System.nanoTime())

    val leastSigBits = numberGenerator.nextLong()

    new UUID(mostSigBits.toLong, leastSigBits)
  }

  private def asUnsigned(unsignedLong: Long) =
    (BigInt(unsignedLong >>> 1) << 1) + (unsignedLong & 1)
}

