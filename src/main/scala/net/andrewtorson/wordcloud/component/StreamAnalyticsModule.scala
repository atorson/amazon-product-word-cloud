package net.andrewtorson.wordcloud.component

import scala.collection.mutable

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
 */
trait StreamAnalyticsModule {

  type In

  type Out

  val wordsCloud: StreamProcessor[In, Out]
}


trait LocalStreamAnalyticsModule extends StreamAnalyticsModule {

  import scala.concurrent.duration._

  override type In = String
  override type Out = TraversableOnce[(String,Long)]

  override val wordsCloud = new FlowProcessor[In, Out, UniqueKillSwitch]{

    override val batchingWindowMillis = 1000

    override val flow = Flow[String].conflateWithSeed(mutable.Buffer[String](_))(_ += _).throttle(1, batchingWindowMillis.milli, 1, ThrottleMode.Shaping)
      .async.map[Seq[String]](_.foldLeft(Seq[String]())(_ ++ EnglishRegexTokenizer.tokenize(_)))
      .viaMat(KillSwitches.single)(Keep.right).map(_.groupBy(identity[String](_)).map{x: (String, Seq[String]) => (x._1, x._2.size.toLong)})
  }

}

trait DistributedStreamAnalyticsModule extends StreamAnalyticsModule {

  import DistributedStoreModule._

  override type In = RDD[String]
  override type Out = RDD [(String,Long)]

  override val wordsCloud = new DStreamProcessor[String,(String,Long)] {

    override val batchingWindowMillis: Int = 1000

    override val flow = {x: DStream[String] => x.flatMap(EnglishRegexTokenizer.tokenize(_)).countByValue()}

  }

  val sparkConf = new SparkConf()
      .setMaster("local[*]")
      .setAppName("AWSWordCloud")
      .set("spark.driver.port", "5678")
      .set("spark.driver.host", "localhost")
    // .set("spark.logConf", "true")
    // .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    // .registerKryoClasses(Array(classOf[Seq[String]]))

  
  lazy val sc = {

      val sc = new StreamingContext(sparkConf, Duration(wordsCloud.batchingWindowMillis))
      sc.checkpoint("./AWSWordCloud_cp")

      val kafkaParams = Map[String, Object](
        "bootstrap.servers" -> kafkaAgent.consumerSettings.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG),
        "key.deserializer" -> kafkaAgent.consumerSettings.keyDeserializerOpt.get.getClass,
        "value.deserializer" -> kafkaAgent.consumerSettings.valueDeserializerOpt.get.getClass,
        "group.id" -> kafkaAgent.consumerSettings.getProperty(ConsumerConfig.GROUP_ID_CONFIG),
        "auto.offset.reset" -> "latest",
        "enable.auto.commit" -> (false: java.lang.Boolean)
      )

      wordsCloud.flow(
        KafkaUtils.createDirectStream[String, String](sc, LocationStrategies.PreferConsistent,
        ConsumerStrategies.Subscribe[String, String](Seq[String](kafkaAgent.topic), kafkaParams))
        .map(_.value)
      ).foreachRDD(x => redisAgent.persist(x.collect()))

      sc

  }

}


