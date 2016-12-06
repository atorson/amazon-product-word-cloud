package net.andrewtorson.wordcloud.store

import scala.collection.immutable.SortedMap
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag

import akka.actor.{Actor, ActorRefFactory, Props}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{Done, NotUsed}
import net.andrewtorson.wordcloud.component.DuplicateKeyPersistenceException

/**
 * Created by Andrew Torson on 12/1/16.
 */
/**
 * API Facade delegating persist/access operations to an Actor holding the actual data
 * All operations are atomic
 *
 * Note: no serialization is needed by nature of local Actors technology
 * All used messages are holding immutable collection structures
 *
 * @param creator
 * @param adviseToFailOnDuplicateKeys
 * @param ev$1
 * @param ev$2
 * @param ev$3
 * @param system
 * @tparam K
 * @tparam V
 * @tparam T
 */
class ActorBasedLocalSubStore[K: ClassTag, V: ClassTag, T <: SubStoreActor: ClassTag](creator: ⇒ T, adviseToFailOnDuplicateKeys: Boolean = false)(implicit system: ActorRefFactory) extends AsyncPersistor[K,V] with SourceAccessor[(K,V)] with AsyncContainsChecker[K]{
  import scala.concurrent.duration._
  protected val actorRef = system.actorOf(Props(creator))
  implicit val defaultTimeout = Timeout(5.seconds)
  implicit val ec = system.dispatcher
  import akka.pattern._

  override def access(): Source[(K,V), NotUsed] = {
    Await.result(ask(actorRef, Access), defaultTimeout.duration).asInstanceOf[Source[(K,V), NotUsed]]
  }

  // persist all and then throws if keys duplicate (if advised to do that)
  override def persist(entires: TraversableOnce[(K, V)]): Future[Done] = {
    ask(actorRef, BatchPersist(entires, adviseToFailOnDuplicateKeys)).map(_ match {
      case true => Done
      case false => throw new DuplicateKeyPersistenceException(s"Duplicate keys found in $entires")
    })
  }

  override def contains(key: K): Future[Boolean] = {
    ask(actorRef, Contains(key)).map(_ match {
      case x: Boolean => x
      case _ => false
    })
  }
}

case class Contains[K: ClassTag](key: K)
case class BatchPersist[K: ClassTag, V: ClassTag](entries: TraversableOnce[(K,V)], adviseToFailOnDuplicateKeys: Boolean)
case object Access

/**
 * This interface wraps the direct collection access and is meant to be overriden in different types of persistors
 * The collections must be derivatives of immutable map: we do not want to handle the mess with updating mutable map iterators
 * @tparam K
 * @tparam V
 * @tparam M specifc data collection type
 */
sealed trait LocalStoreContext[K, V, M<:Map[K,V]] {

  def getEntryIterator(store: M)(): Iterator[(K,V)]

  def batchPersist(entries: TraversableOnce[(K,V)])(store: M): M

  def empty(): M

  def batchRemove(keys: TraversableOnce[K])(store: M): M
}

/**
 * Basic map access interface: replaces duplicate entries
 * @tparam K
 * @tparam V
 */
sealed trait BasicLocalStoreContext[K, V] extends LocalStoreContext[K,V, Map[K,V]]{

  def getEntryIterator(store: Map[K,V])(): Iterator[(K,V)] = store.iterator

  def batchPersist(entries: TraversableOnce[(K,V)])(store: Map[K,V]): Map[K,V] = store ++ entries

  def empty(): Map[K,V] = Map[K,V]()

  def batchRemove(keys: TraversableOnce[K])(store: Map[K,V]): Map[K,V] = keys.foldLeft(store)(_ - _)
}

/**
 * Reducer-type interface: can incrementally aggregate duplicate entry values
 * @tparam K
 * @tparam V
 */
sealed trait ReducerLocalStoreContext[K, V] extends BasicLocalStoreContext[K,V]{

  val reducer: (V,V) => V

  override def batchPersist(entries: TraversableOnce[(K,V)])(store: Map[K,V]) = entries.foldLeft(store)(reduceByKey(_)(_))

  def reduceByKey(map: Map[K,V])(entry: (K,V))  = map + (entry._1 -> (map get entry._1 match {
    case Some(v) => reducer(v, entry._2)
    case _ => entry._2
  }))
}

/**
 * Sorted collection interface: backed by immutable SortedMap (holding a RB-tree within: super-fast O(1) iterator)
 * @tparam K
 * @tparam V
 */
sealed trait SortedLocalStoreContext[K, V] extends LocalStoreContext[K,V, SortedMap[K,V]]{

  implicit val ord: Ordering[K]

  override def getEntryIterator(store: SortedMap[K,V])(): Iterator[(K,V)] = store.iterator

  override def batchPersist(entries: TraversableOnce[(K,V)])(store: SortedMap[K,V]): SortedMap[K,V] = store ++ entries

  override def batchRemove(keys: TraversableOnce[K])(store: SortedMap[K,V]): SortedMap[K,V] = keys.foldLeft(store)(_ - _)

  override def empty(): SortedMap[K,V] = SortedMap[K,V]()
}

/**
 * Marker interface designating a sub-category of actors that can be used by the ActorBasedLocalSubStore Facade
 * Note: experimental Akka-Typed-Actor technology could be used instead
 */
sealed trait SubStoreActor extends Actor {

}

class BasicSubStoreActor[K: ClassTag, V: ClassTag] extends SubStoreActor with BasicLocalStoreContext[K,V]{

  private var innerStore = empty()

  override def receive: Receive = {

    case Contains(key: K) =>{
      sender ! innerStore.contains(key)
    }

    // persist all and then returns boolean (false, if keys duplicate and advised to do that)
    // this is the contract that REST endpoint likes
    case x: BatchPersist[K,V]  => {
      val entries = x.entries.toSeq
      val result = if (x.adviseToFailOnDuplicateKeys && !innerStore.keySet.intersect(entries.map(_._1).toSet).isEmpty) false else true
      innerStore = batchPersist(x.entries)(innerStore)
      sender ! result
    }

    case Access =>
      sender ! Source.fromIterator(getEntryIterator(innerStore))
  }
}

/**
 * This actor has two maps: HashMap and SortedMap
 * It provides a persistor (that goes first to HashMap and ends at SortedMap
 * and accessor that only acesses SortedMap
 *
 * @param reducer reducer to handle incremental persistence (on duplicate entries)
 * @tparam K
 * @tparam V
 */
class SortedSubStoreActor[K: ClassTag, V<% Ordered[V]: ClassTag](override val reducer: ((V,V) => V)) extends SubStoreActor with ReducerLocalStoreContext[K,V] {

  // alas, Scala does not allow to mix this sorted context
  private val sortedContext = new SortedLocalStoreContext[(K,V), NotUsed]{
    override implicit val ord: Ordering[(K,V)] = new Ordering[(K,V)] {
      override def compare(x: (K,V), y: (K,V)): Int =
      //desc ordering
        y._2.compareTo(x._2) match {
            // super-important to handle 0 here
          case 0 => y._1.hashCode().compareTo(x._1.hashCode())
          case v => v
        }
    }
  }

  
  private var basicStore: Map[K,V] = empty()
  private var sortedStore: SortedMap[(K,V), NotUsed] = sortedContext.empty()

  override def receive: Receive = {

    case Contains(key: K) =>{
      sender ! basicStore.contains(key)
    }

     // get keys from request -> get old values in HashMap -> delete corresponding entries in SortedMap
      // then, persist/increment the new values in HashMap -> get the new values and store them in SortedMap
      // this operation is atomic: all messages sent concurrently - are queued in the mailBox while actor is running it

    case x: BatchPersist[K,V]  => {
      val keys = x.entries.map(_._1).toSeq
      sortedStore = sortedContext.batchRemove(keys.flatMap(x => basicStore.get(x).map((x, _))))(sortedStore)
      basicStore =  batchPersist(x.entries)(basicStore)
      sortedStore = sortedContext.batchPersist(keys.flatMap(x => basicStore.get(x).map((x, _))).map((_,NotUsed)))(sortedStore)
      sender ! true
    }

    case Access =>
      sender ! Source.fromIterator(sortedContext.getEntryIterator(sortedStore)).map[(K,V)](_._1)
  }
}



