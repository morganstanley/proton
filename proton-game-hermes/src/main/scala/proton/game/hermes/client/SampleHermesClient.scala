package proton.game.hermes.client

import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{FromResponseUnmarshaller, Unmarshal}
import akka.http.scaladsl.marshalling.{Marshal, Marshaller}
import akka.stream.ActorMaterializer
import proton.game.ProtonAppMeta
import proton.game.hermes.HermesGameProtocol
import proton.game.hermes.HermesGameTickerModule.{AdvertisementDefinition, GameFileCompleteChange}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import proton.game.hermes.HermesGameTickerModulePerRequest.HermesGameTickerModulePerRequestDetailsResult

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

object SampleHermesClient extends App with HermesGameProtocol {

  private implicit val system = ActorSystem()
  private implicit val ec = system.dispatcher
  private implicit val materializer = ActorMaterializer()
  private val http = Http()

  checkHermesIsRunning()
  val gameFileId = createGameFile()


  // Core logic

  def checkHermesIsRunning(): Unit = {
    val hermesRunningCheck = unmarshall[ProtonAppMeta]("http://localhost:8080/")
    if (hermesRunningCheck.name == "Hermes") println("Hermes is running!") else fail(s"Hermes game is not running (${hermesRunningCheck.name} is!)")
  }

  def createGameFile(): UUID = {
    val gameName = "Test Game"
    val game = AdvertisementDefinition(gameName)
    val response = unmarshall[HermesGameTickerModulePerRequestDetailsResult](HttpRequest(uri = "http://localhost:8080/hermes/file", method = HttpMethods.POST, entity = marshal(game)))
    val id = response.id.get
    println(s"Game '${response.name.get}' created with ID: ${id.toString}")
    populateGameFile(id)
    id
  }

  def populateGameFile(id: UUID): Unit = {
    val clientTestResource = Option(getClass.getResourceAsStream("/client_test.csv")).getOrElse(fail("Unable to read client test file!"))
    val csvEntries = scala.io.Source.fromInputStream(clientTestResource).mkString
    unmarshall[HermesGameTickerModulePerRequestDetailsResult](HttpRequest(uri = s"http://localhost:8080/hermes/file/$id/entries", method = HttpMethods.POST, entity = marshal(csvEntries, ContentTypes.`text/csv(UTF-8)`)))

    val complete = GameFileCompleteChange(complete = true)
    unmarshall[HermesGameTickerModulePerRequestDetailsResult](HttpRequest(uri = s"http://localhost:8080/hermes/file/$id/complete", method = HttpMethods.POST, entity = marshal(complete)))

    println("Populated game with test entries")
  }

  System.exit(0)

  // Utility methods!

  private def fail(message: => String): Nothing = {
    System.err.println(message)
    System.exit(-1)
    throw new Exception // So we get our nothing type!
  }

  private def marshal[T](input: T, contentType: ContentType = ContentTypes.`application/json`)(implicit ev: Marshaller[T, RequestEntity]): RequestEntity = {
    Await.result(Marshal(input).to[RequestEntity], Duration.Inf).withContentType(contentType)
  }

  private def unmarshall[T](uri: String)(implicit ev: FromResponseUnmarshaller[T]): T = {
    unmarshall(HttpRequest(uri = uri))
  }

  private def unmarshall[T](httpRequest: HttpRequest)(implicit ev: FromResponseUnmarshaller[T]): T = {
    val futureResponse = http.singleRequest(httpRequest)
    val unmarshalled = futureResponse.flatMap {
      case response if response.status.intValue() == 200 => Unmarshal(response).to[T]
      case other => throw new IllegalStateException(s"${other.status.intValue()} response: ${other.entity.toString}")
    }
    Await.result(unmarshalled, Duration.Inf)
  }

}
