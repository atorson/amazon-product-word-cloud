/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package org.andrewtorson.wordcloud.rest

import javax.ws.rs.Path

import scala.concurrent.Promise
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.{ActorMaterializer, KillSwitches, OverflowStrategy, ThrottleMode}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import io.swagger.annotations._
import org.andrewtorson.wordcloud.component._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import SprayJsonSupport._
import akka.Done
import akka.util.Timeout
import org.andrewtorson.wordcloud.aws.DefaultProductDescriptionFinder.{InvalidProductURLException, MissingProductDescriptionException}
import org.andrewtorson.wordcloud.store.StreamingPublisherActor
import spray.json.DefaultJsonProtocol

case class WordCloud[Repr: ClassTag](topK: Int, wordCounts: Repr)

@Path("/wordcloud")
@Api("/wordcloud")
class ProductDescriptionRoute(val modules: ActorModule with StoreModule with AWSModule) extends Directives {

  type WordCountsSeq = Seq[(String, Int)]
  val REQ_URL_PARAM = "ProductURL"
  val REQ_TOPK_PARAM = "TopK"
  object JsonProtocol extends DefaultJsonProtocol {
    implicit val wordCloudFormat = jsonFormat2(WordCloud[WordCountsSeq])
  }
  import JsonProtocol._
  import scala.concurrent.duration._
  import akka.pattern._

  implicit val ec = modules.system.dispatcher
  implicit val mat = ActorMaterializer()(modules.system)
  implicit val defaultTimeout = Timeout(5.seconds)

  // URL, ProductID and Promise to complete
  type Req = (String, modules.productRetriever.ProductID, Promise[Done])
  // need to throttle AWS requests rate (to avoid 503 response) and buffer the POSTed URLs
  val runningAWSRetrivealFlowMat = Source.actorPublisher[Req](StreamingPublisherActor.props[Req]()).viaMat(
    Flow[Req].buffer(10000000, OverflowStrategy.backpressure).throttle(1, 1000.milli, 1, ThrottleMode.Shaping))(Keep.left)
    .async.viaMat(KillSwitches.single)(Keep.both).toMat(Sink.foreach{x: Req => {
      modules.inDuplicateKeyChecker.contains(x._2).andThen{case Success(b) => {
        if (b) {
          // URL has been processed while waiting in buffer
          x._3.complete(Failure(new DuplicateKeyPersistenceException(s"Product description ${x._2} has already been processed")))
        } else {
          // complete passed promise with URL retrieve + persist future
          x._3.completeWith(
            modules.productRetriever.find(x._1).flatMap{
              z: modules.productRetriever.ProductDescription => modules.inPersistor.persist(Seq((z._1, z._2))).map(_ => Done)}
          )
        }
      }}
      }})(Keep.left).run


  @ApiOperation(value = "Return Top-K Words", notes = "Word cloud based on injested Amazon product descriptions",
    consumes = "application/x-www-form-urlencoded", produces = "application/json", nickname = "", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "TopK", value = "Word Cloud Top-K Slice Size", required = true, dataType = "integer", allowableValues = "range[1, infinity]", defaultValue = "10", paramType = "query")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return Top-K Words", response = classOf[WordCloud[WordCountsSeq]]),
    new ApiResponse(code = 400, message = "Bad Request"),
    new ApiResponse(code = 500, message = "Internal server error")
  ))
  def wordCloudGetRoute =  {
    get {
      parameterMap { fieldMap => {
        fieldMap get REQ_TOPK_PARAM match {
          case Some(topK: String) => {
            try {
              val K = topK.toInt
              onComplete {
                modules.outAccessor.access().take(K).toMat(Sink.fold(Seq[modules.Out]()) { (x, y) => x :+ y })(Keep.right).run()
              } {
                case Success(result: Seq[modules.Out]) => complete(WordCloud[WordCountsSeq](K, result))
                case Failure(ex) => complete(InternalServerError, s"An error occurred: ${ex.getMessage}")
              }
            } catch {
              case x: Throwable => complete(BadRequest, s"The requested topK value $topK is invalid")
            }
          }
          case _ => complete(BadRequest, s"Could not extract topK value from the request query params $fieldMap")
      }}
    }}}

  @ApiOperation(value = "Add Product Description", notes = "Product Description extracted from AWS item lookup", nickname = "", httpMethod = "POST",
    consumes = "application/x-www-form-urlencoded", produces = "text/plain")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "ProductURL", value = "Amazon Product URL, encoded", required = true, dataType = "string", paramType = "query")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 500, message = "Internal server error"),
    new ApiResponse(code = 400, message = "Bad Request"),
    new ApiResponse(code = 201, message = "Product Description Added")
  ))
  def wordCloudPostRoute =  {
    post {
      parameterMap { fieldMap => {
      fieldMap get REQ_URL_PARAM match {
        case Some(url: String) => {
          modules.productRetriever.extract(url) match {
            case Success(locator: modules.productRetriever.ProductLocator) => {
              val id = locator._1
              onComplete {
                modules.inDuplicateKeyChecker.contains(id)
              }{
                case Success(true) => complete(OK, s"Product $id description digest is already present, no fetching/processing required")
                case Success(false) => onComplete {
                  val p = Promise[Boolean]()
                  ask(runningAWSRetrivealFlowMat._1, (url, id, p))
                  p.future
                } {
                  case Success(_) => complete(Created, s"Description of product $id has been successfully processed")
                  case Failure(exc: DuplicateKeyPersistenceException) => complete(OK, s"Product $id description digest is already present, no fetching/processing required")
                  case Failure(exc: MissingProductDescriptionException) => complete(NotFound, exc.getMessage)
                  case Failure(exc) => complete(InternalServerError, s"An error occurred: ${exc.getMessage}")
                }
                case Failure(exc) => complete(InternalServerError, s"An error occurred: ${exc.getMessage}")
              }
            }
            case Failure(exc: InvalidProductURLException) => complete(BadRequest, exc.getMessage)
            case Failure(exc) => complete(InternalServerError, s"An error occurred: ${exc.getMessage}")
          }
        }
        case _ =>  complete(BadRequest, s"Could not extract product URL from the request form $fieldMap")
      }
    }}
  }}

  val routes: Route = wordCloudGetRoute ~ wordCloudPostRoute

}
