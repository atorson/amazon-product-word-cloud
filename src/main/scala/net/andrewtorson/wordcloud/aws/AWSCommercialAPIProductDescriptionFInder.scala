package net.andrewtorson.wordcloud.aws

import javax.xml.parsers.DocumentBuilderFactory


import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}


import com.typesafe.config.Config
import net.andrewtorson.amazonapi.SignedAWSRequestsHelper


/**
 * Created by Andrew Torson on 11/29/16.
 * Retrieves Amazon product descriptions
 * This implementation uses official AWS Commercial API via XML endpoint
 */
class AWSCommercialAPIProductDescriptionFinder(config: Config) extends AmazonURLExtractor{

  // I figured that this is the smallest interval so that AWS does not respond with 503 code
  override val requestIntervalMillis = 1000

  private final val CHARSET = "UTF-8"

    // different product IDs may require different AWS destinations: URL actually defines the destination - so use it
    private final val ENDPOINTS = Map[String, SignedAWSRequestsHelper](
      "com"-> new SignedAWSRequestsHelper(config, "ecs.amazonaws.com"),
      "uk"-> new SignedAWSRequestsHelper(config, "ecs.amazonaws.co.uk"),
      "de"-> new SignedAWSRequestsHelper(config, "ecs.amazonaws.de"),
      "fr"-> new SignedAWSRequestsHelper(config, "ecs.amazonaws.fr"),
      "jp"-> new SignedAWSRequestsHelper(config, "ecs.amazonaws.jp"),
      "ca"-> new SignedAWSRequestsHelper(config, "ecs.amazonaws.ca"))

    private final def getHelper(host: String): SignedAWSRequestsHelper= {
      val parts = host.split(".").reverse
      ENDPOINTS.getOrElse(parts.find(ENDPOINTS.get(_).isDefined).getOrElse("com"), ENDPOINTS.head._2)
    }

    // main method that does all the heavy lifting: uses somewhat old-fashioned official javax XML DOM parser
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
      if (reviews.getLength == 0) throwMissingDescriptionException(productLocator)
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
      if (results.isEmpty) throwMissingDescriptionException(productLocator)
      results.head
    }

    override def find(urlEncoded: String)(implicit ec: ExecutionContext): Future[ProductDescription] =
      Future[ProductDescription] {
       fetchDescription(extract(urlEncoded))
     }

  }
