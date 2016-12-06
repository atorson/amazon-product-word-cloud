package net.andrewtorson.wordcloud.component

import akka.http.scaladsl.server.Route
import net.andrewtorson.wordcloud.rest.ProductDescriptionRoute

/**
 * Created by Andrew Torson on 11/29/16.
 * Defines RESTful HTTP endpoint module (based on Akka-HTTP streaming routes)
 */
trait RestModule {
   val routes: Route
}

trait RestModuleImpl extends RestModule {
  this: ConfigurationModule with ActorModule with StoreModule with CrawlerModule =>

  override val routes = new ProductDescriptionRoute(this).routes

}
