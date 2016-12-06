/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package net.andrewtorson.wordcloud

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

import akka.Done
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.{Route, RouteConcatenation}
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import net.andrewtorson.wordcloud.component._
import net.andrewtorson.wordcloud.rest.{CorsSupport, SwaggerDocService}


object Main extends App with RouteConcatenation with CorsSupport with LazyLogging{

  val modules = new ConfigurationModuleImpl  with ActorModuleImpl with
    DistributedStoreModuleImplementation with DistributedStreamAnalyticsModule with AWSModuleImpl with RestModuleImpl

  // Akka implicits
  implicit val system = modules.system
  implicit val materializer = ActorMaterializer()
  implicit val ec = modules.system.dispatcher

  launch()

  def launch() = {
    try {
      // start Spark
      val p = Promise[Done]()
      new Thread(){
        override def run() = {
          try {
            val sc = modules.sc
            sc.start()
            p.success(Done)
            sc.awaitTermination()
          } catch {
            case x: Throwable => p.failure(x)
          }
        }
      }.start()
      Await.ready(p.future, Duration.Inf)
      // start HTTP server
      val swaggerService = new SwaggerDocService(system)
      Await.result(Http().bindAndHandle(Route.handlerFlow(
        swaggerService.assets ~
          corsHandler(swaggerService.routes) ~ modules.routes), "localhost", 8080), Duration.Inf)
      logger.info("Launched HTTP server at http://localhost:8080")
    } catch {
      case x: Throwable => {
       //val message = s"Fatal error starting application: $x"
      // logger.error(message)
       sys.error(s"Fatal error starting application: $x")
      }
    }
  }



}