package proton.game

object GameEvents {
  val Unhandled = -1
  val Timeout = -2
  val PlayerInvalid = 101
  val ConfigInvalid = 102
  val StateInvalid = 103
  val NotOpen = 104
  val CommandInvalid = 105
  val WrongNumberOfPlayers = 106
  val ControlCommandInvalid = 107

  val InitializationError = 201
  val MaxBytesReached = 202
  val RequiresAuth = 203
  val ObservingGame = 204
  val PlayerJoined = 205
  val PlayerJoinedAgain = 206
  val PlayerNotAuthorized = 207
  val Transitioned = 208
  val Complete = 209
  val ObserverCommandReceived = 210
  val PlayerCommandInvalid = 211
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