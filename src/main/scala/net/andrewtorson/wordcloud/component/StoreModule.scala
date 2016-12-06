package net.andrewtorson.wordcloud.component

import scala.concurrent.Future
import scala.language.reflectiveCalls

import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, ProducerSettings}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.redis.RedisClient
import com.redis.serialization.DefaultFormats
import net.andrewtorson.wordcloud.store._
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}



/**
 * Created by Andrew Torson on 11/28/16.
 */



trait StoreModule {

  type KeyIn = String
  type ValueIn = String
  type KeyOut = String
  type ValueOut = Long
  type ReprOut = (KeyOut, ValueOut)

  val inPersistor: AsyncPersistor[KeyIn,ValueIn]
  val outAccessor: SourceAccessor[ReprOut]
  val inDuplicateKeyChecker: AsyncContainsChecker[KeyIn]

}


trait LocalStoreModuleImplementation extends StoreModule {

  this: ActorModule with LocalStreamAnalyticsModule =>

  import scala.concurrent.duration._

  implicit val actorSystem: ActorSystem = system
  implicit val ec = system.dispatcher
  implicit val mat = ActorMaterializer()
  implicit val defaultTimeout = Timeout(5.seconds)

  override val outAccessor = new ActorBasedLocalSubStore[KeyOut, ValueOut, SortedSubStoreActor[KeyOut, ValueOut]](new SortedSubStoreActor[KeyOut, ValueOut](_ +_))

  private val runningWordCloudFlowMat = Source.actorPublisher[ValueIn](StreamingPublisherActor.props[ValueIn]()).viaMat(wordsCloud.flow)(Keep.both).toMat(
    Sink.foreach(outAccessor.persist(_)))(Keep.left).run

  // combined actor + local key store agent
  private val publisher = new AsyncPersistor[KeyIn,ValueIn] with AsyncContainsChecker[KeyIn] {
    import akka.pattern._
    // this key store will fail on duplicate keys
    private val keyStore = new ActorBasedLocalSubStore[KeyIn, NotUsed, BasicSubStoreActor[KeyIn, NotUsed]](new BasicSubStoreActor[KeyIn, NotUsed], true)

    def persist(id: KeyIn, value: ValueIn): Future[Done] = {
      // exactly-once guaranteed by key-checking persistor
      keyStore.persist(Seq((id,NotUsed))).flatMap(_ => ask(runningWordCloudFlowMat._1, value).map(_ => Done))
    }

    override def persist(entires: TraversableOnce[(KeyIn, ValueIn)]): Future[Done] = {
      Future.sequence(entires.map(x => persist(x._1, x._2))).map(_=>Done)
    }

    override def contains(key: KeyIn): Future[Boolean] = keyStore.contains(key)

  }

  override val inPersistor = publisher

  override val inDuplicateKeyChecker = publisher


}

object DistributedStoreModule {

  private val actorSystem = ActorSystem("Akka-DistributedModules")
  private val ctx = actorSystem.dispatcher
  private val redisClient = RedisClient("localhost")(actorSystem)

  val redisAgent = new RedisSortedSetAgent[String, Long] {

    override protected val client = redisClient
    override implicit val ec = ctx
    override val cacheName = "AWSWordCloud"
    override val cutoff: Int = scala.math.pow(2,10).toInt
    override implicit val num = Numeric.LongIsIntegral
    override implicit val writer = DefaultFormats.stringFormat
    override implicit val reader = DefaultFormats.stringFormat

    val redisBitSetAgent = new RedisBitSetAgent[String, String] {
      override val cachePrefix = "AWSProducts"
      override implicit val ec = ctx
      override protected val client = redisClient
    }
  }

  val kafkaAgent = new KafkaTopic[String, String] with KafkaProducer[String, String] {
    override implicit val ec = ctx
    override val topic = "AWSProductDescriptions"
    override val producerSettings = ProducerSettings(actorSystem, new StringSerializer, new StringSerializer)
      .withBootstrapServers("localhost:9092")
    override val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers("localhost:9092")
      .withGroupId(topic)
  }
}

trait DistributedStoreModuleImplementation extends StoreModule {

  import DistributedStoreModule._

  // we want to connect to Kafka on start-up - so invoking this to initialize lazy val kafkaProducer
  kafkaAgent.kafkaProducer.flush()

  override val inDuplicateKeyChecker = redisAgent.redisBitSetAgent
  override val outAccessor = redisAgent
  override val inPersistor = new AsyncPersistor[KeyIn, ValueIn] {

    import redisAgent.ec

    def persist(record: (KeyIn, ValueIn)): Future[Done] = {
      val records = Seq(record)
      // exactly-once guaranteed by key-checking persistor
      redisAgent.redisBitSetAgent.persist(records).flatMap(_ => kafkaAgent.persist(records))
    }

    override def persist(entries: TraversableOnce[(KeyIn, ValueIn)]): Future[Done] = {
      Future.sequence(entries.map(persist(_))).map(_ => Done)
    }
  }
}

class DuplicateKeyPersistenceException(message: String) extends RuntimeException (message)





