/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 26, 2016
 */

package org.andrewtorson.wordcloud.store

import scala.annotation.tailrec
import scala.reflect.ClassTag

import akka.{Done}
import akka.actor.{ActorLogging, Props}
import akka.stream.actor.ActorPublisher



/**
 * Created by Andrew Torson on 8/26/16.
 */


object StreamingPublisherActor{

  def props[T: ClassTag](dropHead: Boolean = true): Props =
    Props (new StreamingPublisherActor[T](dropHead))

}

class StreamingPublisherActor[T: ClassTag] (dropHead: Boolean) extends ActorPublisher[T] with ActorLogging {

  case object QueueUpdated

  import akka.stream.actor.ActorPublisherMessage._
  import scala.collection.mutable


  val queue = mutable.Queue[T]()
  var queueUpdated: Boolean = false
  val bufferSize = 1000

  override def receive = {
    case data: T => {
      if (queue.size >= bufferSize) {
        if (dropHead){
          log.debug(s"Buffer overflow: dropping new value = $data")
        } else {
          log.debug(s"Buffer overflow: dropping tail value = ${queue.dequeue}")
        }
      }
      if (queue.size < bufferSize) {
        queue += data
        sender ! Done
        if (!queueUpdated) {
          queueUpdated = true
          self ! QueueUpdated
        }
      }
    }

    case QueueUpdated => {
      deliver()
    }

    case Request(amount) =>
      deliver()

    // subscriber stops, so we stop ourselves.
    case Cancel => {}
      context.stop(self)

  }

  /**
   * Deliver the message to the subscriber
   */
  @tailrec final def deliver(): Unit = {
    if (queue.size == 0 && totalDemand != 0) {
      queueUpdated = false
    } else if (totalDemand > 0 && queue.size > 0) {
      val m = queue.dequeue()
      onNext(m)
      deliver()
    }
  }

}


