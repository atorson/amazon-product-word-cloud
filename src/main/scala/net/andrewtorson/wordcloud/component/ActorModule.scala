/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package net.andrewtorson.wordcloud.component

import akka.actor.ActorSystem


trait ActorModule {
  val system: ActorSystem
}


trait ActorModuleImpl extends ActorModule {
  this: ConfigurationModule =>
  val system = ActorSystem("AkkaWordCloud", config)
}