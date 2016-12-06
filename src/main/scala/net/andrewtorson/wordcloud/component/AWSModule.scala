package net.andrewtorson.wordcloud.component

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import net.andrewtorson.wordcloud.aws.{AsyncProductDescriptionFinder, AWSCommercialAPIProductDescriptionFinder}

/**
 * Created by Andrew Torson on 11/30/16.
 */
trait AWSModule {
  val productRetriever: AsyncProductDescriptionFinder
}


trait AWSModuleImpl extends AWSModule{
  override val productRetriever = AWSCommercialAPIProductDescriptionFinder
}