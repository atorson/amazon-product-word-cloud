package org.andrewtorson.wordcloud.component

import scala.collection.mutable

import akka.stream._
import akka.stream.scaladsl.{Flow, Keep}
import org.andrewtorson.wordcloud.streamanalytics.{EnglishRegexTokenizer, SparkWordCloudProcessor}


/**
 * Created by Andrew Torson on 11/30/16.
 */
trait StreamAnalyticsModule {
  type Result = Long
  val wordsCloud: BagOfWordsStreamProcessor[Result]
}

trait StreamProcessor[In,Out] {
  def  batchingWindowMillis: Int
}

trait FlowProcessor[In,Out, Mat] extends StreamProcessor[In,Out]{
   val flow: Flow[In, Out, Mat]
}

trait WordsTokenizer {
  def tokenize(in: String): Seq[String]
}

trait BagOfWordsStreamProcessor[Result] extends StreamProcessor[String, TraversableOnce[(String,Result)]]{

   val tokenizer: WordsTokenizer

   def resultsAggregator: Seq[String] => Result
}

trait BagOfWordsFlowProcessor[Result] extends BagOfWordsStreamProcessor[Result] with FlowProcessor[String, TraversableOnce[(String,Result)], UniqueKillSwitch]{
  import scala.concurrent.duration._


  override val flow =
     Flow[String].conflateWithSeed(mutable.Buffer[String](_))(_ += _).throttle(1, batchingWindowMillis.milli, 1, ThrottleMode.Shaping)
       .async.map[Seq[String]](_.foldLeft(Seq[String]())(_ ++ tokenizer.tokenize(_)))
       .viaMat(KillSwitches.single)(Keep.right).map(_.groupBy(identity[String](_)).map{x: (String, Seq[String]) => (x._1, resultsAggregator(x._2))})



}

trait LocalStreamAnalyticsModule extends StreamAnalyticsModule {

  override val wordsCloud = new BagOfWordsFlowProcessor[Result] {
    override def batchingWindowMillis: Int = 1000
    override def resultsAggregator: (Seq[String]) => Result = _.size.toLong
    override val tokenizer: WordsTokenizer = EnglishRegexTokenizer
  }

}

trait DistributedStreamAnalyticsModule extends StreamAnalyticsModule {

  this: DistributedStoreModuleImplementation =>

  override val wordsCloud = new SparkWordCloudProcessor {

    override def batchingWindowMillis = 1000
    override def resultsAggregator = _.size.toLong
    override val tokenizer = EnglishRegexTokenizer
    override val kafkaTopic = kafkaAgent
    override val persistor = redisAgent
  }

}


