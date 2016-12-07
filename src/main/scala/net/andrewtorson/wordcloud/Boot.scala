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

  // Cake DI pattern in action below

  // Injection is driven by command line options rather than a configuration file
  //
  // Note1: Proper DI framework (Guice or Spring Context) is a lot more handy in managing monolith apps (like this)
  // because there is no need to enumerate on a cartesian product of non-auto-wired options
  // here, there's just 2 options - so it is not worth the effort (extra dependency and loss of bootstrapping control) to bring a DI framework
  //
  // Note2: another alternative is to use regular composition and delegation via implicit conversion/Pimp My Library pattern
  // But this pattern is only meant for simple one-trait cases: it can't handle the injection flow orchestration
  //
  // Note3: yet another alternative is to use some kind of 'Injector' Free Monad pattern (using a reflective class constructor to instantiate dependencies)
  // This would be a great way to have our own simple DI flow implementation
  // But it also not worth the effort (extra lines of code) for the simple case of 2 options)
  //
  // So let's stick with Cake even though the code looks somewhat ugly (2x2 cartesian product enumeration, repeating ourselves etc.) below

  try {
    // BFG option (as in, Doom BFG9000) indicates Distributed
    val modules  = (args.contains("BFG")) match {

      case true => {

        // AWS indicates official Amazon AWS XML endpoint service instead of Amazon HTML scraping
        val result = (args.contains("AWS")) match {

          case true => new ConfigurationModuleImpl with ActorModuleImpl with
            DistributedStoreModuleImplementation with DistributedStreamAnalyticsModule with AWSCrawlerModuleImpl with RestModuleImpl

          // by default use Amazon HTML scraper
          case _ =>   new ConfigurationModuleImpl with ActorModuleImpl with
            DistributedStoreModuleImplementation with DistributedStreamAnalyticsModule with AmazonScraperCrawlerModuleImpl with RestModuleImpl
        }

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

      // by default, start in Local mode
      case _ => {

        (args.contains("AWS")) match {

          case true => new ConfigurationModuleImpl with ActorModuleImpl with
            LocalStreamAnalyticsModule with LocalStoreModuleImplementation with AWSCrawlerModuleImpl with RestModuleImpl

          case _ => new ConfigurationModuleImpl with ActorModuleImpl with
            LocalStreamAnalyticsModule with LocalStoreModuleImplementation with AmazonScraperCrawlerModuleImpl with RestModuleImpl
        }
      }
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