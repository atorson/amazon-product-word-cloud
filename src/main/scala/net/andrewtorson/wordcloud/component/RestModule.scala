package net.andrewtorson.wordcloud.component

import akka.http.scaladsl.server.Route
import net.andrewtorson.wordcloud.rest.ProductDescriptionRoute

/**
 * Created by Andrew Torson on 11/29/16.
 */
trait RestModule {
   val routes: Route
}

trait RestModuleImpl extends RestModule {
  this: ActorModule with StoreModule with AWSModule =>

  override val routes = new ProductDescriptionRoute(this).routes

}
