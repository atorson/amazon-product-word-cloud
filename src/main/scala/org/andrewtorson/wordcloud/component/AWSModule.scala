package org.andrewtorson.wordcloud.component

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import org.andrewtorson.wordcloud.aws.DefaultProductDescriptionFinder

/**
 * Created by Andrew Torson on 11/30/16.
 */
trait AWSModule {
  val productRetriever: AsyncProductDescriptionFinder
}

trait AsyncProductDescriptionFinder {
  type ProductID = String
  type Host = String
  type ProductLocator = (ProductID, Host)
  type ProductDescription = (ProductID, String)
  def extract(urlEncoded: String): Try[ProductLocator]
  def find(urlEncoded: String)(implicit ec: ExecutionContext): Future[ProductDescription]
}

trait AWSModuleImpl extends AWSModule{
  override val productRetriever = DefaultProductDescriptionFinder
}