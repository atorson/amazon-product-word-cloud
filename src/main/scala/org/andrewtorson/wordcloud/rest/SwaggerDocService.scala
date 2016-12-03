/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package org.andrewtorson.wordcloud.rest

import scala.reflect.runtime.{universe => ru}

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.github.swagger.akka._
import com.github.swagger.akka.model.Info
import io.swagger.models.Swagger


class SwaggerDocService(system: ActorSystem) extends SwaggerHttpService with HasActorSystem {
  override implicit val actorSystem: ActorSystem = system
  override implicit val materializer: ActorMaterializer = ActorMaterializer()
  override val apiTypes = Seq(ru.typeOf[ProductDescriptionRoute])
  override val host = "localhost:8080"
  override val info = Info(version = "2.0")

  def assets = pathPrefix("swagger") {
    getFromResourceDirectory("swagger") ~ pathSingleSlash(get(redirect("index.html", StatusCodes.PermanentRedirect))) }

}
