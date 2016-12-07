
package net.andrewtorson.wordcloud.rest

import javax.ws.rs.Path

import scala.concurrent.Promise
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

import akka.Done
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directives, Route}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorMaterializer, KillSwitches, OverflowStrategy, ThrottleMode}
import akka.util.Timeout
import io.swagger.annotations._
import net.andrewtorson.wordcloud.aws.{InvalidProductURLException, MissingProductDescriptionException}
import net.andrewtorson.wordcloud.component._
import net.andrewtorson.wordcloud.store.StreamingPublisherActor
import spray.json.DefaultJsonProtocol


/**
 * Simple domain model for the Word Cloud application
 * @param topK how many word cloud items requested
 * @param wordCounts actual return
 * @tparam Repr representation format (that can be derived from word counts)
 */
case class WordCloud[Repr: ClassTag](topK: Int, wordCounts: Repr)

/**
 * This class defines the Akka-HTTP RESTful endpoint for the WordCloud of Product Description words
 * @param modules quite a lot of modules: need Crawl, Persist and Access services
 */
@Path("/wordcloud")
@Api("/wordcloud")
class ProductDescriptionRoute(val modules: ActorModule with StoreModule with CrawlerModule) extends Directives {

  // very simple representation: no conversion on top of what is accessed
  type WordCountsRepr = Seq[modules.ReprOut]

  // these are the query params for the POST and GET methods in this endpoint
  val REQ_URL_PARAM = "ProductURL"
  val REQ_TOPK_PARAM = "TopK"

  // Spray-JSON serializer for the GET method response
  object JsonProtocol extends DefaultJsonProtocol {
    implicit val wordCloudFormat = jsonFormat2(WordCloud[WordCountsRepr])
  }
  import scala.concurrent.duration._

  import JsonProtocol._
  import akka.pattern._

  implicit val ec = modules.system.dispatcher
  implicit val mat = ActorMaterializer()(modules.system)
  implicit val defaultTimeout = Timeout(5.seconds)

  // URL, ProductID and Promise to complete
  type Req = (String, modules.productRetriever.ProductID, Promise[Done])

  // just a simple formula to buffer more if retrieval is slow
  val requestIntervalMillis = modules.productRetriever.requestIntervalMillis
  val requestBufferSize = requestIntervalMillis * 1000

  // need to throttle AWS requests rate (to avoid 503 response) and buffer the POSTed URLs
  // there is a small chance that there may be duplicate keys in this flow (despite of prior contains check):
  // it is the case when a batch of requests accumulated over 1000 millis has duplicate URLs that were not cached yet
  // we will hit AWS in this case (not a big deal, compared to the cost of asking 'contains' again on every request)
  // and we let the persistor handle it as it seems fit
  // e.g. it should only persist once and fail all duplicate requests with DuplicateKeysException
  val runningAWSRetrivealFlowMat = Source.actorPublisher[Req](StreamingPublisherActor.props[Req]()).viaMat(
    Flow[Req].buffer(requestBufferSize, OverflowStrategy.backpressure).throttle(1, requestIntervalMillis.millis, 1, ThrottleMode.Shaping))(Keep.left)
    .async.viaMat(KillSwitches.single)(Keep.both).toMat(Sink.foreach{x: Req => {
          // complete passed promise with the future from retrieve + persist pipeline
          // this makes the response delayed (without blocking) - but actual meaningful response can be returned then
          x._3.completeWith(
            modules.productRetriever.find(x._1).flatMap{
              z: modules.productRetriever.ProductDescription => modules.inPersistor.persist(Seq((z._1, z._2))).map(_ => Done)}
          )
      }})(Keep.left).run


  @ApiOperation(value = "Return Top-K Words", notes = "Word cloud based on injested Amazon product descriptions",
    consumes = "application/x-www-form-urlencoded", produces = "application/json", nickname = "", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "TopK", value = "Word Cloud Top-K Slice Size", required = true, dataType = "integer", allowableValues = "range[1, 1000]", defaultValue = "10", paramType = "query")
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Return Top-K Words", response = classOf[WordCloud[WordCountsRepr]]),
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
                // advantage of Source[] type of accessor: can lazily iterate at arbitrary length
                modules.outAccessor.access().take(K).toMat(Sink.fold(Seq[modules.ReprOut]()) { (x, y) => x :+ y })(Keep.right).run()
              } {
                case Success(result: Seq[modules.ReprOut]) => complete(WordCloud[WordCountsRepr](K, result))
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
          Try{modules.productRetriever.extract(url)} match {
            case Success(locator: modules.productRetriever.ProductLocator) => {
              // check if the product has already been processed: no Crawling and Processing will be done - super-fast response
              val id = locator._1
              onComplete {
                modules.inDuplicateKeyChecker.contains(id)
              }{
                case Success(true) => complete(OK, s"Product $id description digest is already present, no fetching/processing required")
                case Success(false) => onComplete {
                  // inject into the throttled flow which will complete the future
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
