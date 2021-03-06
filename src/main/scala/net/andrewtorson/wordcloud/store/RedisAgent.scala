package net.andrewtorson.wordcloud.store

import java.nio.ByteBuffer
import java.util.UUID

import scala.concurrent.{Await, ExecutionContext, Future}

import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{Done, NotUsed}
import com.redis.RedisClient
import com.redis.serialization.{Reader, ScoredValue, Writer}
import net.andrewtorson.wordcloud.component.DuplicateKeyPersistenceException

/**
 * Created by Andrew Torson on 12/3/16.
 */
/**
 * Wrapper interface for Redis client connections
 */
trait RedisAgent{

  import scala.concurrent.duration._

  implicit val ec: ExecutionContext
  implicit val defaultTimeout = Timeout(5.seconds)

  protected val client: RedisClient

}

/**
 * Peristor and key-contains checker backed by Redist BitSet
 * @tparam K
 * @tparam V
 */
trait RedisBitSetAgent[K,V] extends RedisAgent with AsyncPersistor[K,V] with AsyncContainsChecker[K]{

  import scala.math._
  val cachePrefix: String

  // first entry is Redis sub-cache, second is the offset in that sub-cache
  type RedisLocator = (String, Int)

  // number of Redis sub-caches used: each Redis cache is maxed out at 512Mb and allows only Int offsets
  val N: Int = pow(2, 10).toInt

  val rootUUID = UUID.fromString("d4d1c83d-d63a-46cf-8597-d6e8d2a0b0ba")

  // generates 128-bit Java UUID - and uses leastSignificant bits for sub-cache and most-signficant for offset
  final protected def getLocator(key: K): RedisLocator = {
      val uuid = getUUID(rootUUID, key.toString)
      // miniscule chance of cache collisions: this is fine as it will result in extreme rare cache miss that is certainly not critical
      (cachePrefix + abs(uuid.getLeastSignificantBits % N).toString, abs(uuid.getMostSignificantBits.hashCode()))
  }

  final protected def getUUID(namespace: UUID, childName: String): UUID = {
    val childBytes = childName.getBytes
    UUID.nameUUIDFromBytes(ByteBuffer.allocate(16 + childBytes.size).
      putLong(namespace.getMostSignificantBits).
      putLong(namespace.getLeastSignificantBits).
      put(childBytes).array)
  }


  // persist all and then throws if keys duplicate: this is the contract that REST endpoint likes
  override def persist(entries: TraversableOnce[(K, V)]): Future[Done] = {

    Future.sequence(entries.map(x => {val y = getLocator(x._1)
      client.setbit(y._1,y._2, true)
    })).map(_.foldLeft(true)((x,y) => x && (y == 0L)) match {
      case true => Done
      case _ => throw new DuplicateKeyPersistenceException(s"Duplicate keys found in $entries")
    })
  }

  override def contains(key: K): Future[Boolean] = {
    val y = getLocator(key)
    client.getbit(y._1, y._2)
  }
}

/**
 * Persistor and accessor backed by Redis SortedSet
 * @tparam K
 * @tparam V
 */
trait RedisSortedSetAgent[K,V] extends RedisAgent with AsyncPersistor[K,V] with SourceAccessor[(K,V)]{

  val cacheName: String

  val cutoff: Int

  implicit val num: Numeric[V]
  implicit val writer: Writer[K]
  implicit val reader: Reader[K]

  // incremental persistence (via zincrby() suitable for streaming)
  override def persist(entries: TraversableOnce[(K, V)]): Future[Done] = {
    Future.sequence(
      ScoredValue.applySeq(entries.map(x => (x._2, x._1)).toSeq).map(z => client.zincrby(cacheName, z.score, z.value))
    ).map(_ =>Done)
  }

  private final def getIterator(): Iterator[(K,V)] = // inverted range iterator!
    Await.result(client.zrevrangeWithScores(cacheName, 0, cutoff).map(
      _.map(x => (x._1, num.fromInt(x._2.toInt)))),defaultTimeout.duration).iterator

  override def access(): Source[(K, V), NotUsed] = {
   Source.fromIterator(getIterator)
  }
}
