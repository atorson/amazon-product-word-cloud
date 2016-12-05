package org.andrewtorson.wordcloud.store

import scala.concurrent.{ExecutionContext, Future, Promise}

import akka.Done
import akka.kafka.{ConsumerSettings, ProducerSettings}
import org.andrewtorson.wordcloud.component.AsyncPersistor
import org.apache.kafka.clients.producer.{Callback, Producer, ProducerRecord, RecordMetadata}


/**
 * Created by Andrew Torson on 12/3/16.
 */

trait KafkaTopic[K,V]{

  val topic: String

  val producerSettings: ProducerSettings[K,V]
  val consumerSettings: ConsumerSettings[K,V]
}

trait KafkaProducer[K,V] extends KafkaTopic[K,V] with AsyncPersistor[K,V]{

  implicit val ec: ExecutionContext

  lazy val kafkaProducer = producerSettings.createKafkaProducer()

  override def persist(entries: TraversableOnce[(K, V)]): Future[Done] = {
    Future.sequence(entries.map(x=>{
      val p = Promise[Done]()
      val c = new Callback {
        override def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
          if (exception == null)
            p.success(Done)
          else
            p.failure(exception)
        }
      }
      kafkaProducer.send(new ProducerRecord[K,V](topic, x._1,x._2), c)
      p.future
    })).map(_ => Done)
  }
}
