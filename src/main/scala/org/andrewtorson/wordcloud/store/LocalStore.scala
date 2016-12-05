package org.andrewtorson.wordcloud.store

import scala.collection.immutable.SortedMap
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{FiniteDuration, _}
import scala.reflect.ClassTag

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorRefFactory, Props}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import org.andrewtorson.wordcloud.component.{AsyncContainsChecker, AsyncPersistor, DuplicateKeyPersistenceException, SourceAccessor}

/**
 * Created by Andrew Torson on 12/1/16.
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

sealed trait LocalStoreContext[K, V, M<:Map[K,V]] {

  def getEntryIterator(store: M)(): Iterator[(K,V)]

  def batchPersist(entries: TraversableOnce[(K,V)])(store: M): M

  def empty(): M

  def batchRemove(keys: TraversableOnce[K])(store: M): M
}

sealed trait BasicLocalStoreContext[K, V] extends LocalStoreContext[K,V, Map[K,V]]{

  def getEntryIterator(store: Map[K,V])(): Iterator[(K,V)] = store.iterator

  def batchPersist(entries: TraversableOnce[(K,V)])(store: Map[K,V]): Map[K,V] = store ++ entries

  def empty(): Map[K,V] = Map[K,V]()

  def batchRemove(keys: TraversableOnce[K])(store: Map[K,V]): Map[K,V] = keys.foldLeft(store)(_ - _)
}

sealed trait ReducerLocalStoreContext[K, V] extends BasicLocalStoreContext[K,V]{

  val reducer: (V,V) => V

  override def batchPersist(entries: TraversableOnce[(K,V)])(store: Map[K,V]) = entries.foldLeft(store)(reduceByKey(_)(_))

  def reduceByKey(map: Map[K,V])(entry: (K,V))  = map + (entry._1 -> (map get entry._1 match {
    case Some(v) => reducer(v, entry._2)
    case _ => entry._2
  }))
}

sealed trait SortedLocalStoreContext[K, V] extends LocalStoreContext[K,V, SortedMap[K,V]]{

  implicit val ord: Ordering[K]

  override def getEntryIterator(store: SortedMap[K,V])(): Iterator[(K,V)] = store.iterator

  override def batchPersist(entries: TraversableOnce[(K,V)])(store: SortedMap[K,V]): SortedMap[K,V] = store ++ entries

  override def batchRemove(keys: TraversableOnce[K])(store: SortedMap[K,V]): SortedMap[K,V] = keys.foldLeft(store)(_ - _)

  override def empty(): SortedMap[K,V] = SortedMap[K,V]()
}

sealed trait SubStoreActor extends Actor {

}

class BasicSubStoreActor[K: ClassTag, V: ClassTag] extends SubStoreActor with BasicLocalStoreContext[K,V]{

  private var innerStore = empty()

  override def receive: Receive = {

    case Contains(key: K) =>{
      sender ! innerStore.contains(key)
    }

    // persist all and then returns boolean (false, if keys duplicate and advised to do that)
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

class SortedSubStoreActor[K: ClassTag, V<% Ordered[V]: ClassTag](override val reducer: ((V,V) => V)) extends SubStoreActor with ReducerLocalStoreContext[K,V] {

  // alas, Scala does not allow to mix this sorted context
  private val sortedContext = new SortedLocalStoreContext[(K,V), NotUsed]{
    override implicit val ord: Ordering[(K,V)] = new Ordering[(K,V)] {
      override def compare(x: (K,V), y: (K,V)): Int =
      //desc ordering
        y._2.compareTo(x._2) match {
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



