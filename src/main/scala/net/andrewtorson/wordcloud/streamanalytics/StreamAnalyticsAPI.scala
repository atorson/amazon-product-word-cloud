package net.andrewtorson.wordcloud.streamanalytics

import akka.stream.scaladsl.Flow

import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.DStream

/**
 * Created by Andrew Torson on 12/5/16.
 */

/**
 * Marker micro-batching stream processor interface
 * @tparam In
 * @tparam Out
 */
trait StreamProcessor[In,Out] {

  val  batchingWindowMillis: Int

}

/**
 * Akka-Streams Flow-backed processor
 * @tparam In
 * @tparam Out
 * @tparam Mat
 */
trait FlowProcessor[In,Out, Mat] extends StreamProcessor[In,Out]{

  val flow: Flow[In, Out, Mat]

}

/**
 * Spark DStream-backed processor
 * @tparam In
 * @tparam Out
 */
trait DStreamProcessor[In, Out] extends StreamProcessor[RDD[In], RDD[Out]] {

  // retoric: why Spark Streaming does not introduce a Flow[In,Out] construct??
  val flow: DStream[In] => DStream[Out]

}

/**
 * Word tokenizer analytic
 */
trait WordsTokenizer {

  def tokenize(in: String): Seq[String]

}
