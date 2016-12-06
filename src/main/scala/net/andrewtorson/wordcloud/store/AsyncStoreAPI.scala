package net.andrewtorson.wordcloud.store

import scala.concurrent.Future

import akka.{Done, NotUsed}
import akka.stream.scaladsl.Source

/**
 * Created by Andrew Torson on 12/5/16.
 */

trait AsyncPersistor[K,V] {

  def persist(entries: TraversableOnce[(K,V)]): Future[Done]

}


trait AsyncContainsChecker[K] {

  def contains(key: K): Future[Boolean]

}

trait SourceAccessor[E] {

  def access(): Source[E, NotUsed]

}

