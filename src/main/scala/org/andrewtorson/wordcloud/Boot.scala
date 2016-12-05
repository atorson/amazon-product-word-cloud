/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package org.andrewtorson.wordcloud



import scala.concurrent.Await
import scala.concurrent.duration.Duration


import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.{Route, RouteConcatenation}
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import org.andrewtorson.wordcloud.component._
import org.andrewtorson.wordcloud.rest.{CorsSupport, SwaggerDocService}


object Main extends App with RouteConcatenation with CorsSupport with LazyLogging{

  val modules = new ConfigurationModuleImpl  with ActorModuleImpl with
    DistributedStoreModuleImplementation  with DistributedStreamAnalyticsModule with AWSModuleImpl with RestModuleImpl

  // Akka implicits
  implicit val system = modules.system
  implicit val materializer = ActorMaterializer()
  implicit val ec = modules.system.dispatcher

  //ToDo: add shutdown hooks and launch spark on separate thread
  launch()

  def launch() = {
    try {
      // start Spark
      modules.wordsCloud.sc.start()
      // start HTTP server
      val swaggerService = new SwaggerDocService(system)
      Await.result(Http().bindAndHandle(Route.handlerFlow(
        swaggerService.assets ~
          corsHandler(swaggerService.routes) ~ modules.routes), "localhost", 8080), Duration.Inf)
      logger.info("Launched HTTP server at http://localhost:8080")
    } catch {
      case x: Throwable =>  sys.error(s"Fatal error starting application")
    }
  }



}