/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package net.andrewtorson.wordcloud.component

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}

trait ConfigurationModule {
  val config: Config
}

trait ConfigurationModuleImpl extends ConfigurationModule {

  override val config: Config = {
    val configDefaults = ConfigFactory.load(this.getClass().getClassLoader(), "wordcloud.conf")
    
    scala.sys.props.get("application.config") match {
      case Some(filename) => ConfigFactory.parseFile(new File(filename)).withFallback(configDefaults)
      case None => configDefaults
    }
  }
  
}