package net.andrewtorson.wordcloud.aws

import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

import scala.collection.immutable.ListMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

import net.andrewtorson.amazonapi.SignedAWSRequestsHelper


/**
 * Created by Andrew Torson on 11/29/16.
 */
object AWSCommercialAPIProductDescriptionFinder extends AsyncProductDescriptionFinder{
    private final val CHARSET = "UTF-8"
    private final val ENDPOINTS = ListMap[String, SignedAWSRequestsHelper](
      "com"-> SignedAWSRequestsHelper.getInstance("ecs.amazonaws.com"),
      "uk"->SignedAWSRequestsHelper.getInstance("ecs.amazonaws.co.uk"),
      "de"->SignedAWSRequestsHelper.getInstance("ecs.amazonaws.de"),
      "fr"->SignedAWSRequestsHelper.getInstance("ecs.amazonaws.fr"),
      "jp"->SignedAWSRequestsHelper.getInstance("ecs.amazonaws.jp"),
      "ca"->SignedAWSRequestsHelper.getInstance("ecs.amazonaws.ca"))

    private final def getHelper(host: String): SignedAWSRequestsHelper= {
      val parts = host.split(".")
      for ( endpoint <- ENDPOINTS){
        if (parts.contains(endpoint._1)){
          return endpoint._2;
        }
      }
      return ENDPOINTS.values.head
    }


  override def extract(urlEncoded: String): Try[ProductLocator] =
   try {
     val parts = URLDecoder.decode (urlEncoded, CHARSET).split ("/")
     val host = parts.find (_.contains ("amazon") ).get
     val productID = parts (parts.indexWhere (_.contains ("product") ) + 1)
     Success(productID, host)
   } catch {
     case x: Throwable => throwInvalidURLException(urlEncoded)
   }


    class MissingProductDescriptionException (message: String) extends RuntimeException (message)
    class InvalidProductURLException(message: String) extends RuntimeException(message)

    private final def throwMissingDescriptionException(id: ProductID, host: String): Nothing = {
      throw new MissingProductDescriptionException (s"Product $id description is missing on $host")
    }

    private final def throwInvalidURLException(url: String): Nothing = {
      throw new InvalidProductURLException(s"Product URL $url is invalid")
    }

    private def fetchDescription(productLocator: ProductLocator): ProductDescription = {

    import scala.collection.JavaConversions._

    val host = productLocator._2
    val productID = productLocator._1

    val encodedURL = getHelper (host).sign (Map[String, String] (
    "Service" -> "AWSECommerceService",
    "Operation" -> "ItemLookup",
    "ResponseGroup" -> "EditorialReview",
    "ItemId" -> productID
    ))

    val reviews = DocumentBuilderFactory.newInstance ().newDocumentBuilder ().parse (encodedURL).getElementsByTagName ("EditorialReviews")
    if (reviews.getLength == 0) throwMissingDescriptionException (productID, host)
    val list = reviews.item (0).getChildNodes ()
    val results = mutable.Buffer[(String, String)] ()
    for (i <- Range (0, list.getLength () ) ) {
      try {
         val values = list.item (i).getChildNodes ()
         if (values.item (0).getChildNodes ().item (0).getNodeValue ().equals ("Product Description") ) {
            val s: ProductDescription = (productID, values.item (1).getChildNodes ().item (0).getNodeValue () )
            results += s
        }
      } catch {
        case x: Throwable => {}
      }
    }
    if (results.isEmpty) throwMissingDescriptionException (productID, host)
    results.head
  }

    override def find(urlEncoded: String)(implicit ec: ExecutionContext): Future[ProductDescription] =
      Future[ProductDescription] {
       fetchDescription(extract(urlEncoded).get)
      }

  }
