package net.andrewtorson.wordcloud.aws

import java.net.URLDecoder

import scala.concurrent.{ExecutionContext, Future}


/**
 * Created by Andrew Torson on 12/5/16.
 * Defines the product finder (by URL) interface
 */

trait AsyncProductDescriptionFinder {

  type ProductID = String
  type Host = String
  type ProductLocator = (ProductID, Host)  // e.g. Amazon ASIN + Amazon website name
  type ProductDescription = (ProductID, String)

  // defines request rate  acceptable for the underlying web-service
  val requestIntervalMillis: Int

  @throws(classOf[InvalidProductURLException])
  def extract(urlEncoded: String): ProductLocator

  @throws(classOf[InvalidProductURLException])
  @throws(classOf[MissingProductDescriptionException])
  def find(urlEncoded: String)(implicit ec: ExecutionContext): Future[ProductDescription]

  final def throwMissingDescriptionException(locator: ProductLocator): Nothing = {
    throw new MissingProductDescriptionException(s"Product ${locator._1} description is missing on ${locator._2}")
  }

  final def throwInvalidURLException(url: String): Nothing = {
    throw new InvalidProductURLException(s"Product URL $url is invalid")
  }
}

trait AmazonURLExtractor extends AsyncProductDescriptionFinder {

  // Amazon specific HTML 'product' endpoint format:
  override def extract(urlEncoded: String): ProductLocator =
    try {
      val parts = URLDecoder.decode(urlEncoded, "UTF-8").split ("/")
      val host = parts.find (_.contains ("amazon") ).get
      val productID = parts (parts.indexWhere (_.contains ("product") ) + 1)
      (productID, host)
    } catch {
      case x: Throwable => throwInvalidURLException(urlEncoded)
    }
}

class MissingProductDescriptionException (message: String) extends RuntimeException (message)

class InvalidProductURLException(message: String) extends RuntimeException(message)