package proton.game

trait GameEndPoint {
  def connectionString: String
  def init()
}
