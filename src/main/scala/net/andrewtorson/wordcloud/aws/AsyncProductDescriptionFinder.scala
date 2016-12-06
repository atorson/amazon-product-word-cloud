package net.andrewtorson.wordcloud.aws

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Created by Andrew Torson on 12/5/16.
 */
trait AsyncProductDescriptionFinder {
  type ProductID = String
  type Host = String
  type ProductLocator = (ProductID, Host)
  type ProductDescription = (ProductID, String)
  def extract(urlEncoded: String): Try[ProductLocator]
  def find(urlEncoded: String)(implicit ec: ExecutionContext): Future[ProductDescription]
}
