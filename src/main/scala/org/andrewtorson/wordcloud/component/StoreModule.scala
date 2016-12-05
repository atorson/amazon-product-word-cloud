package org.andrewtorson.wordcloud.component

import scala.concurrent.Future

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.redis.RedisClient
import com.redis.serialization.DefaultFormats
import org.andrewtorson.wordcloud.store._
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import scala.language.reflectiveCalls

import akka.kafka.{ConsumerSettings, ProducerSettings}
import akka.pattern._
import akka.util.Timeout



/**
 * Created by Andrew Torson on 11/28/16.
 */



trait StoreModule {

  type KeyIn = String
  type In = String
  type KeyOut = String
  type ValueOut = Long
  type Out = (KeyOut, ValueOut)

  val inPersistor: AsyncPersistor[KeyIn,In]
  val outAccessor: SourceAccessor[Out]
  val inDuplicateKeyChecker: AsyncContainsChecker[KeyIn]

}

trait AsyncPersistor[K,V] {

  def persist(entries: TraversableOnce[(K,V)]): Future[Done]

}

trait AsyncContainsChecker[K] {

  def contains(key: K): Future[Boolean]

}

trait SourceAccessor[E] {

  def access(): Source[E, NotUsed]

}

trait LocalStoreModuleImplementation extends StoreModule {

  this: ActorModule with LocalStreamAnalyticsModule =>

  import scala.concurrent.duration._

  implicit val actorSystem: ActorSystem = system
  implicit val ec = system.dispatcher
  implicit val mat = ActorMaterializer()
  implicit val defaultTimeout = Timeout(5.seconds)

  override val outAccessor = new ActorBasedLocalSubStore[KeyOut, ValueOut, SortedSubStoreActor[KeyOut, ValueOut]](new SortedSubStoreActor[KeyOut, ValueOut](_ +_))

  private val runningWordCloudFlowMat = Source.actorPublisher[In](StreamingPublisherActor.props[In]()).viaMat(wordsCloud.flow)(Keep.both).toMat(
    Sink.foreach(outAccessor.persist(_)))(Keep.left).run

  // combined actor + local key store agent
  private val publisher = new AsyncPersistor[KeyIn,In] with AsyncContainsChecker[KeyIn] {
    import akka.pattern._
    // this key store will fail on duplicate keys
    private val keyStore = new ActorBasedLocalSubStore[KeyIn, NotUsed, BasicSubStoreActor[KeyIn, NotUsed]](new BasicSubStoreActor[KeyIn, NotUsed], true)

    def persist(id: KeyIn, value: In): Future[Done] = {
      // exactly-once guaranteed by key-checking persistor
      keyStore.persist(Seq((id,NotUsed))).flatMap(_ => ask(runningWordCloudFlowMat._1, value).map(_ => Done))
    }

    override def persist(entires: TraversableOnce[(KeyIn, In)]): Future[Done] = {
      Future.sequence(entires.map(x => persist(x._1, x._2))).map(_=>Done)
    }

    override def contains(key: KeyIn): Future[Boolean] = keyStore.contains(key)

  }

  override val inPersistor = publisher

  override val inDuplicateKeyChecker = publisher


}

trait DistributedStoreModuleImplementation extends StoreModule {
  this: ActorModule  =>

  implicit val actorSystem: ActorSystem = system

  val redisAgent = new  RedisSortedSetAgent[KeyOut, ValueOut] {
    private val redisClient = RedisClient("localhost")
    override protected val client = redisClient
    override implicit val ec = actorSystem.dispatcher
    override val cacheName = "AWSWordCloud"
    override val cutoff: Int = scala.math.pow(2,10).toInt
    override implicit val num = Numeric.LongIsIntegral
    override implicit val writer = DefaultFormats.stringFormat
    override implicit val reader = DefaultFormats.stringFormat

    val redisBitSetAgent = new RedisBitSetAgent[KeyIn, In] {
      override val cachePrefix = "AWSProducts"
      override implicit val ec = actorSystem.dispatcher
      override protected val client = redisClient
    }
  }

  val kafkaAgent = new KafkaTopic[KeyIn, In] with KafkaProducer[KeyIn,In] {
    override implicit val ec = actorSystem.dispatcher
    override val topic = "AWSProductDescriptions"
    override val producerSettings = ProducerSettings(actorSystem, new StringSerializer, new StringSerializer)
      .withBootstrapServers("localhost:9092")
    override val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers("localhost:9092")
      .withGroupId(topic)
  }
  override val inDuplicateKeyChecker = redisAgent.redisBitSetAgent
  override val outAccessor = redisAgent
  override val inPersistor = new AsyncPersistor[KeyIn, In] {

    implicit val ec = actorSystem.dispatcher

    def persist(record: (KeyIn, In)): Future[Done] = {
      val records = Seq(record)
      // exactly-once guaranteed by key-checking persistor
      redisAgent.redisBitSetAgent.persist(records).flatMap(_ => kafkaAgent.persist(records))
    }

    override def persist(entries: TraversableOnce[(KeyIn, In)]): Future[Done] = {
      Future.sequence(entries.map(persist(_))).map(_ => Done)
    }
  }
}

class DuplicateKeyPersistenceException(message: String) extends RuntimeException (message)





