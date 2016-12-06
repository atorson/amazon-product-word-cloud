
package net.andrewtorson.wordcloud.rest

import akka.http.scaladsl.marshalling.ToResponseMarshallable.apply
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCode.int2StatusCode
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directive.addByNameNullaryApply
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Route}

/**
 * Defines pluggable support of CORS for an HTTP endpoint
 */
trait CorsSupport {
  //see https://groups.google.com/forum/#!topic/akka-user/5RCZIJt7jHo
  //this directive adds access control headers to normal responses
  private def addAccessControlHeaders: Directive0 = {
    mapResponseHeaders { headers =>
      `Access-Control-Allow-Origin`.* +:
        `Access-Control-Allow-Credentials`(true) +:
        `Access-Control-Allow-Headers`("Authorization", "Content-Type", "X-Requested-With") +:
        headers
    }
  }

  //this handles preflight OPTIONS requests
  //otherwise has to be under addAccessControlHeaders
  private def preflightRequestHandler: Route = options {
    complete(HttpResponse(200).withHeaders(
      `Access-Control-Allow-Methods`(OPTIONS, POST, PUT, GET, DELETE)
    )
    )
  }

  /**
   * Decorates the route with CORS directive
   * @param r any Akka-HTTP route
   * @return decorated route
   */
  def corsHandler(r: Route) = addAccessControlHeaders {
    preflightRequestHandler ~ r
  }
}