package org.andrewtorson.wordcloud.component

import scala.concurrent.{Future}

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer}
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Keep, Sink, Source}
import org.andrewtorson.wordcloud.store._



/**
 * Created by Andrew Torson on 11/28/16.
 */



trait StoreModule {

  type KeyIn = String
  type In = String
  type KeyOut = String
  type Out = (KeyOut, Int)

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
  this: ActorModule with BasicStreamAnaluticsModule =>
  implicit val actorSystem: ActorSystem = system
  implicit val ec = system.dispatcher
  implicit val mat = ActorMaterializer()

  override val outAccessor = new ActorBasedLocalSubStore[KeyOut, Int, SortedSubStoreActor[KeyOut, Int]](new SortedSubStoreActor[KeyOut, Int](_ +_))
  val runningWordCloudFlowMat = Source.actorPublisher[In](StreamingPublisherActor.props[In]()).viaMat(wordsCloud.flow)(Keep.both).toMat(
    Sink.foreach(outAccessor.persist(_)))(Keep.left).run
  // advising to fail on duplicate input keys to provide exactly-once guarantee for the word cloud flow
  private val publisher = new ActorPublisherPersistor[KeyIn, In](runningWordCloudFlowMat._1,
    new ActorBasedLocalSubStore[KeyIn, NotUsed, BasicSubStoreActor[KeyIn, NotUsed]](new BasicSubStoreActor[KeyIn, NotUsed], true))
  override val inPersistor = publisher
  override val inDuplicateKeyChecker = publisher


}

class DuplicateKeyPersistenceException(message: String) extends RuntimeException (message)





