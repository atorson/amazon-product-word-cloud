package net.andrewtorson.wordcloud.store

import scala.concurrent.Future

import akka.{Done, NotUsed}
import akka.stream.scaladsl.Source

/**
 * Created by Andrew Torson on 12/5/16.
 */

/**
 * Async persist
 * @tparam K
 * @tparam V
 */
trait AsyncPersistor[K,V] {

  def persist(entries: TraversableOnce[(K,V)]): Future[Done]

}

/**
 * Async contains check
 * @tparam K
 */
trait AsyncContainsChecker[K] {

  def contains(key: K): Future[Boolean]

}

/**
 * Async access (lazy streaming Source that can be implicitly pulled by its consumer downstream)
 * @tparam E
 */
trait SourceAccessor[E] {

  def access(): Source[E, NotUsed]

}

