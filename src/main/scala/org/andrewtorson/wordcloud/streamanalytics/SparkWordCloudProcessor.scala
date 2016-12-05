package org.andrewtorson.wordcloud.streamanalytics


import org.andrewtorson.wordcloud.component.{AsyncPersistor, BagOfWordsStreamProcessor}
import org.andrewtorson.wordcloud.store.KafkaTopic
import org.apache.kafka.clients.consumer.ConsumerConfig


import org.apache.spark.SparkConf
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Duration, StreamingContext}

/**
 * Created by Andrew Torson on 12/4/16.
 */


trait SparkWordCloudProcessor extends BagOfWordsStreamProcessor[Long] {


  type KeyIn = String
  type ValueIn = String
  type KeyOut = String

  val kafkaTopic: KafkaTopic[KeyIn, ValueIn]

  val persistor: AsyncPersistor[KeyOut, Long]

  val sparkConf = new SparkConf()
    .setMaster("local[*]")
    .setAppName("AWSWordCloud")
    .set("spark.driver.port", "5678")
    .set("spark.driver.host", "localhost")
    .set("spark.logConf", "true")
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .registerKryoClasses(Array(classOf[(KeyIn, ValueIn)]))


  lazy val sc = {

      val sc = new StreamingContext(sparkConf, Duration(batchingWindowMillis))
      sc.checkpoint("./AWSWordCloud_cp")

      val kafkaParams = Map[String, Object](
        "bootstrap.servers" -> kafkaTopic.consumerSettings.getProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG),
        "key.deserializer" -> kafkaTopic.consumerSettings.keyDeserializerOpt.get.getClass,
        "value.deserializer" -> kafkaTopic.consumerSettings.valueDeserializerOpt.get.getClass,
        "group.id" -> kafkaTopic.consumerSettings.getProperty(ConsumerConfig.GROUP_ID_CONFIG),
        "auto.offset.reset" -> "latest",
        "enable.auto.commit" -> (false: java.lang.Boolean)
      )
      KafkaUtils.createDirectStream[KeyIn, ValueIn](sc, LocationStrategies.PreferBrokers,
        ConsumerStrategies.Subscribe[KeyIn, ValueIn](Seq[String](kafkaTopic.topic), kafkaParams)
      )
      // tokenize and flatten
      .flatMap(x => tokenizer.tokenize(x.value()))
      // countByValue
      .countByValue()
      //persist (incremental persistence is managed by persistor)
      .foreachRDD(rdd => {
        persistor.persist(rdd.collect())
      })

      sc

  }
}

