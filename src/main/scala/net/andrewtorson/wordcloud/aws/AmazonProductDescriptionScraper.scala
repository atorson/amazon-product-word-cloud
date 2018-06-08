package net.andrewtorson.wordcloud.aws
import java.net.URLDecoder

import scala.concurrent.{ExecutionContext, Future}

import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._


/**
 * Created by Andrew Torson on 12/6/16.
 */
object AmazonProductDescriptionScraper extends AmazonURLExtractor{

  // let's hammer Amazon HTML endpoints pretty hard - it still responds fast
  override val requestIntervalMillis: Int = 100

  // Amazon product HTML pages are typically text + images rather than complex JS views
  // no unsafe JS running needed
  private val browser = new JsoupBrowser()

  override def find(urlEncoded: String)(implicit ec: ExecutionContext) =  Future[ProductDescription] {
      val locator = extract(urlEncoded)
      val descriptionOpt = browser.get(URLDecoder.decode(urlEncoded, "UTF-8")) >?> element("#productDescription p")
      if (descriptionOpt.isEmpty) throwMissingDescriptionException(locator)
      (locator._1, descriptionOpt.get.text)
  }
}
