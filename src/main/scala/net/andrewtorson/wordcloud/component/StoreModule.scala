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
 * Defines In/Out storage interface based on (atomic) async persist/access operations
 */
trait StoreModule {
  // default type values are tailored for the Word Cloud application
  type KeyIn = String
  type ValueIn = String
  type KeyOut = String
  type ValueOut = Long
  type ReprOut = (KeyOut, ValueOut)

  val inPersistor: AsyncPersistor[KeyIn,ValueIn]
  val outAccessor: SourceAccessor[ReprOut]
  val inDuplicateKeyChecker: AsyncContainsChecker[KeyIn]

}

/**
 * Local in-memory store implementation backed by a combo of HashMap + TreeMap
 * Very fast operations: no network-hop and all operations are O(1) (including sorted source access)
 * The time-performance efficiency is achieved at the expense of space (in-memory storage) which is OK for medium-scale data
 * All operations are atomic (queries are sent to an Actor holding actual maps)
 * All data will be lost on JVM shutdown: this is the main drawback of this implementation
 * 
 * Note: it is easy to change it to use Akka-Cluster and its DistributedData extension to cache in a cluster-wide in-memory-persistent cache
 * Akka-Cluster can be asked to fully replicate on every update - so the data replica will always be available in-memory at every node
 * In Local implementation this does not make much sense: background Distributed Data serialization will create a huge cost for nothing
 */
trait LocalStoreModuleImplementation extends StoreModule {

  this: ActorModule with LocalStreamAnalyticsModule =>

  import scala.concurrent.duration._

  implicit val actorSystem: ActorSystem = system
  implicit val ec = system.dispatcher
  implicit val mat = ActorMaterializer()
  implicit val defaultTimeout = Timeout(5.seconds)

  // the accessor uses incremental peristance, adding and reducing scores in a HashMap and then updating a sorted map with the results
  override val outAccessor = new ActorBasedLocalSubStore[KeyOut, ValueOut, SortedSubStoreActor[KeyOut, ValueOut]](new SortedSubStoreActor[KeyOut, ValueOut](_ +_))

  // this flow materializes the Streaming Analytics by connecting it with the persistor and accessor private access
  // uses Akka-Streams library
  private val runningWordCloudFlowMat = Source.actorPublisher[ValueIn](StreamingPublisherActor.props[ValueIn]()).viaMat(wordsCloud.flow)(Keep.both).toMat(
    Sink.foreach(outAccessor.persist(_)))(Keep.left).run

  // combined actor + local key store agent: input is split so that keys go to key store and values go to the analytics stream
  private val publisher = new AsyncPersistor[KeyIn,ValueIn] with AsyncContainsChecker[KeyIn] {
    import akka.pattern._
    // this key store will fail on duplicate keys
    // this store is backed by a HashMap of keys: it is a waste of memory to store keys instead of bits - could use a huge bunch of Scala BitSets instead
    // however it is not really worth it in the Local implementation - the accessor is storing a lot more info already
    private val keyStore = new ActorBasedLocalSubStore[KeyIn, NotUsed, BasicSubStoreActor[KeyIn, NotUsed]](new BasicSubStoreActor[KeyIn, NotUsed], true)

    def persist(id: KeyIn, value: ValueIn): Future[Done] = {
      // exactly-once guaranteed by key-checking persistor: message won't be streamed if key is a duplicate
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

/**
 * Global singleton holding the Redis and Kafka connections
 * It is needed only because of Spark serialization constraints (Spark will load and use this singleton on every executor node)
 */
object DistributedStoreModule extends ConfigurationModuleImpl{

  private val wordCloudConfig = config.getConfig("wordcloud")// this field will be cleaned up by Scala compiler because it is only used in the constructor

  // both Redis and Kafka connections use Akka streaming I/O
  // it is better to define a separate actor system for this private I/O operations - though we could re-use a one
  private val actorSystem = ActorSystem("Akka-DistributedModules")
  private val ctx = actorSystem.dispatcher
  private val redisClient = {
    val address = getAddress("redis").get
    RedisClient(address.getHostName, address.getPort)(actorSystem)
  }

  val redisAgent = new RedisSortedSetAgent[String, Long] {

    override protected val client = redisClient
    override implicit val ec = ctx
    override val cacheName = wordCloudConfig.getString("sortedResultsCacheName")
    override val cutoff: Int = wordCloudConfig.getInt("maxK")
    override implicit val num = Numeric.LongIsIntegral
    override implicit val writer = DefaultFormats.stringFormat
    override implicit val reader = DefaultFormats.stringFormat

    val redisBitSetAgent = new RedisBitSetAgent[String, String] {
      override val cachePrefix = wordCloudConfig.getString("keyCachePrefix")
      override implicit val ec = ctx
      override protected val client = redisClient
    }
  }

  val kafkaAgent = new KafkaTopic[String, String] with KafkaProducer[String, String] {
    private val address = getAddress("kafka").get.toString // this field will be cleaned up by Scala compiler because it is only used in the constructor
    override implicit val ec = ctx
    override val topic = wordCloudConfig.getString("topicName")
    override val producerSettings = ProducerSettings(actorSystem, new StringSerializer, new StringSerializer)
      .withBootstrapServers(address)
    override val consumerSettings = ConsumerSettings(actorSystem, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(address)
      .withGroupId(topic)
  }
}

/**
 * Distributed store implementation backed by Redis BitSet, SortedSet and Kafka topic queue
 */
trait DistributedStoreModuleImplementation extends StoreModule {

  import DistributedStoreModule._

  // we want to connect to Kafka on start-up - so invoking this to initialize lazy val kafkaProducer
  kafkaAgent.kafkaProducer.flush()

  override val inDuplicateKeyChecker = redisAgent.redisBitSetAgent

  //redis agent provides incremental score persistence via zincrby() command
  override val outAccessor = redisAgent

  override val inPersistor = new AsyncPersistor[KeyIn, ValueIn] {

    import redisAgent.ec

    def persist(record: (KeyIn, ValueIn)): Future[Done] = {
      val records = Seq(record)
      // exactly-once guaranteed by key-checking persistor: duplicates won't be injected into Kafka
      redisAgent.redisBitSetAgent.persist(records).flatMap(_ => kafkaAgent.persist(records))
    }

    override def persist(entries: TraversableOnce[(KeyIn, ValueIn)]): Future[Done] = {
      Future.sequence(entries.map(persist(_))).map(_ => Done)
    }
  }
}

class DuplicateKeyPersistenceException(message: String) extends RuntimeException (message)





