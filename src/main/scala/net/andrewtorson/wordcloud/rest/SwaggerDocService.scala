
package net.andrewtorson.wordcloud.rest

import java.net.InetSocketAddress

import scala.reflect.runtime.{universe => ru}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import com.github.swagger.akka._
import com.github.swagger.akka.model.Info

/**
 * Swagger API documentation and GUI service powered by Akka-HTTP
 * @param system actor system
 * @param address Swagger destination redirect address
 */
class SwaggerDocService(system: ActorSystem, address: InetSocketAddress) extends SwaggerHttpService with HasActorSystem {
  override implicit val actorSystem: ActorSystem = system
  override implicit val materializer: ActorMaterializer = ActorMaterializer()
  override val apiTypes = Seq(ru.typeOf[ProductDescriptionRoute])
  override val host = s"${address.getHostString}:${address.getPort}"  //Swagger can't deal with toString() format = localhost/127.0.0.1 addresses
  override val info = Info(version = "2.0")

  def assets = pathPrefix("swagger") {
    getFromResourceDirectory("swagger") ~ pathSingleSlash(get(redirect("index.html", StatusCodes.PermanentRedirect))) }

}
