package net.andrewtorson.wordcloud.component

import scala.collection.mutable
import scala.util.Try

import akka.stream._
import akka.stream.scaladsl.{Flow, Keep}
import net.andrewtorson.wordcloud.streamanalytics.{DStreamProcessor, EnglishRegexTokenizer, FlowProcessor, StreamProcessor}
import org.apache.kafka.clients.consumer.ConsumerConfig

import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Duration, StreamingContext}


/**
 * Created by Andrew Torson on 11/30/16.
 * This component defines the streaming processing analytics/logic
 */
trait StreamAnalyticsModule {

  type In

  type Out

  // just the wordCloud processor in this module
  val wordsCloud: StreamProcessor[In, Out]
}

/**
 * Local in-memory re-active stream implementation based on Akka-Streams
 */
trait LocalStreamAnalyticsModule extends StreamAnalyticsModule {

  this: ConfigurationModule =>

  import scala.concurrent.duration._

  override type In = String
  override type Out = TraversableOnce[(String,Long)]

  override val wordsCloud = new FlowProcessor[In, Out, UniqueKillSwitch]{

    override val batchingWindowMillis = config.getConfig("wordcloud").getInt("batchingIntervalMillis")

    // the flow buffers over batching window (to manage the load on sorting persistor), tokenizes the buffer and counts by by key
    override val flow = Flow[String].conflateWithSeed(mutable.Buffer[String](_))(_ += _).throttle(1, batchingWindowMillis.milli, 1, ThrottleMode.Shaping)
      .async.map[Seq[String]](_.foldLeft(Seq[String]())(_ ++ EnglishRegexTokenizer.tokenize(_)))
      .viaMat(KillSwitches.single)(Keep.right).map(_.groupBy(identity[String](_)).map{x: (String, Seq[String]) => (x._1, x._2.size.toLong)})
  }

}

/**
 * Dsitributed micro-batching stream implementation based on Spark Streaming
 */
trait DistributedStreamAnalyticsModule extends StreamAnalyticsModule {

  import DistributedStoreModule._

  override type In = RDD[String]
  override type Out = RDD [(String,Long)]

  override val wordsCloud = new DStreamProcessor[String,(String,Long)] {

    override val batchingWindowMillis = config.getConfig("wordcloud").getInt("batchingIntervalMillis")

    // just a one-liner: map with Tokenizer and count. Batching is managed by Spark
    override val flow = {x: DStream[String] => x.flatMap(EnglishRegexTokenizer.tokenize(_)).countByValue()}

  }

  private val address = getAddress("spark").get// this field will be cleaned up by Scala compiler because it is only used in the constructor

  val sparkConf = new SparkConf()
      .setMaster("local[*]")
      .setAppName(config.getConfig("spark").getString("appName"))
      .set("spark.driver.host", address.getHostName)
      .set("spark.driver.port", address.getPort.toString)

  
  lazy val sc = {

      val sc = new StreamingContext(sparkConf, Duration(wordsCloud.batchingWindowMillis))

      // this app flow is not critical (can affordmiss some in-flight URLs when Spark job fails)
      // and does not use interim stateful RDDs - so does not really need mandatory checkpointing
      Try{val checkpointPath = config.getConfig("spark").getString("checkpointPath")
          if (!checkpointPath.isEmpty) sc.checkpoint(checkpointPath)}

      // we could delegate the hook-up to other modules that instantiate and hold the input stream
      // but we could also do it here - becase input is coming from Kafka and there is no strong coupling
      // between producing and consuming in Pub/Sub

      // grap the Kafka consumer connection params from global singleton
      val kafkaParams = Map[String, Object](
        "bootstrap.servers" -> kafkaAgent.consumerSettings.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG),
        "key.deserializer" -> kafkaAgent.consumerSettings.keyDeserializerOpt.get.getClass,
        "value.deserializer" -> kafkaAgent.consumerSettings.valueDeserializerOpt.get.getClass,
        "group.id" -> kafkaAgent.consumerSettings.getProperty(ConsumerConfig.GROUP_ID_CONFIG),
        "auto.offset.reset" -> "latest",
        "enable.auto.commit" -> (false: java.lang.Boolean)
      )
      // create input stream from Kafka
      wordsCloud.flow(
        KafkaUtils.createDirectStream[String, String](sc, LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe[String, String](Seq[String](kafkaAgent.topic), kafkaParams))
        .map(_.value) // and persist via the Redis persistor singleton
      ).foreachRDD(x => redisAgent.persist(x.collect())) // no need to use Spark accumulators: let the persistor manage increments (Redis handles it well)

      sc

  }

}


