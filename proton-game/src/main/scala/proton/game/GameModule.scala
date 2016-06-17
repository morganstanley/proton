package proton.game

import akka.http.scaladsl.server.Route

trait GameModule {
  def name:String
  def route: Route
  def init(): Unit
}
