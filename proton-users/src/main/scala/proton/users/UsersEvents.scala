package proton.users

object UsersEvents {
  val Unhandled = -1
}

case class Validation(message: String, errorCode: Int, exception: Option[Throwable] = None)
case class ValidationException(validation: Validation) extends Exception {
  def this(message: String, errorCode: Int, exception: Option[Throwable] = None) =
    this(Validation(message, errorCode, exception))
}
object ValidationException {
  def apply(message: String, errorCode: Int, exception: Option[Throwable] = None): ValidationException =
    new ValidationException(Validation(message, errorCode, exception))
}

case class Message(summary: String, errorCode: Int)