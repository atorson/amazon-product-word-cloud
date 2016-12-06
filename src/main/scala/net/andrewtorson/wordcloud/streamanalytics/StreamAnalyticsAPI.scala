package net.andrewtorson.wordcloud.streamanalytics

import akka.stream.scaladsl.Flow

import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.DStream

/**
 * Created by Andrew Torson on 12/5/16.
 */

trait StreamProcessor[In,Out] {

  val  batchingWindowMillis: Int

}

trait FlowProcessor[In,Out, Mat] extends StreamProcessor[In,Out]{

  val flow: Flow[In, Out, Mat]

}

trait DStreamProcessor[In, Out] extends StreamProcessor[RDD[In], RDD[Out]] {

  val flow: DStream[In] => DStream[Out]

}

trait WordsTokenizer {

  def tokenize(in: String): Seq[String]

}
