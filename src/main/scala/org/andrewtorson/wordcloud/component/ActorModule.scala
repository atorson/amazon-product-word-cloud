/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package org.andrewtorson.wordcloud.component

import akka.actor.ActorSystem


trait ActorModule {
  val system: ActorSystem
}


trait ActorModuleImpl extends ActorModule {
  this: Configuration =>
  val system = ActorSystem("AkkaWordCloud", config)
}