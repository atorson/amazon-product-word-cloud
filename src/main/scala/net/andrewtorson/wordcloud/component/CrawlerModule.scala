package net.andrewtorson.wordcloud.component

import net.andrewtorson.wordcloud.aws.{AsyncProductDescriptionFinder, AWSCommercialAPIProductDescriptionFinder}

/**
 * Created by Andrew Torson on 11/30/16.
 * Defines a crawler module capable of retrieving product descriptions (remotely)
 */
trait CrawlerModule {
  val productRetriever: AsyncProductDescriptionFinder
}


trait AWSCrawlerModuleImpl extends CrawlerModule{
  this: ConfigurationModule =>
  override val productRetriever = new AWSCommercialAPIProductDescriptionFinder(config.getConfig("aws"))
}