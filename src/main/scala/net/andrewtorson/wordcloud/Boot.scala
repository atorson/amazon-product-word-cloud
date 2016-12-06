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

/**
 * Main launchable app for WordCloud of Product Descriptions RESTful service
 */
object Main extends App with RouteConcatenation with CorsSupport with LazyLogging{

  // a few modes supported
  // could define a HYBRID mode which is LOCAL + Redis Store
  // but didn't bother as it is inferior to a (more complex but better) (yet unimplmented)
  // option of using Akka-Cluster Distributed-Data cache (see LocalStoreModuleImpl comments)
  object WordCloudAppModes extends Enumeration {
    val LOCAL, DISTRIBUTED = Value
  }

  import WordCloudAppModes._
  // by default, start in local mode
  // BFG option (as in, Doom BFG9000) indicates Distributed
  val mode = if (!args.isEmpty && args(0).startsWith("BFG")) DISTRIBUTED else LOCAL
  try {
    val modules = mode match {
      case DISTRIBUTED => {
        // Cake DI pattern in action
        val result = new ConfigurationModuleImpl with ActorModuleImpl with
        DistributedStoreModuleImplementation with DistributedStreamAnalyticsModule with AWSCrawlerModuleImpl with RestModuleImpl
        // start Spark locally on a separate thread
        // could use a Spark Launcher though - just need to re-organize this project
        // into a two-project SBT where second depends on first
        // with spark code and its dependencies (Redis, Kafka etc) contained in the first project
        // and the launcher contained in the second
        val p = Promise[Done]()
        new Thread(){
          override def run() = {
            try {
              val sc = result.sc
              sc.start()
              p.success(Done)
              sc.awaitTermination()
            } catch {
              case x: Throwable => p.failure(x)
            }
          }
        }.start()
        Await.ready(p.future, Duration.Inf)
        result
      }

      case LOCAL => {
        // Cake DI pattern in action
        new ConfigurationModuleImpl with ActorModuleImpl with
        LocalStreamAnalyticsModule with LocalStoreModuleImplementation with AWSCrawlerModuleImpl with RestModuleImpl}
    }

    // Akka implicits
    implicit val system = modules.system
    implicit val materializer = ActorMaterializer()
    implicit val ec = modules.system.dispatcher

    // start HTTP server
    val swaggerService = new SwaggerDocService(system, modules.getAddress("swagger").get)
    val httpAddress = modules.getAddress("http").get
    Await.result(Http().bindAndHandle(Route.handlerFlow(
      swaggerService.assets ~
        corsHandler(swaggerService.routes) ~ modules.routes), httpAddress.getHostName, httpAddress.getPort), Duration.Inf)

    logger.info(s"Launched HTTP server at: $httpAddress")

    // how to handle partial restarts? They are not handled now
    //
    // Can introduce an ApplicationService trait with start/stop methods
    // Each module that is stateful and restartable - should implement it
    // E.g. HTTP service holds a ServerBiding that can be used to stop the service - so that Http.bindAndHandle() can be used to restart
    // Or modules with Akka-Flows hold a UniqueKillSwitch that can be used to kill (restart is more tricky but can be done)
    // Spark can be stopped and re-started too
    //
    // Then the 'modules' instance (which is trully holding the entire App monolith) does not need to be re-instantiated
    // This way, in Local mode the data can be preserved (restart should not erase it) unless JVM is going down
    //
  } catch {
    case x: Throwable => {
      sys.error(s"Fatal error starting application: $x")
    }
  }

}