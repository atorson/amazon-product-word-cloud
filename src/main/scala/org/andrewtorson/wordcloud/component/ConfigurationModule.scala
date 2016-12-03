/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package org.andrewtorson.wordcloud.component

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}

trait Configuration {
  val config: Config
}

object Configuration {
  def apply(): Configuration = default
  val default = new ConfigurationModuleImpl {}
}

trait ConfigurationModuleImpl extends Configuration {

  override val config: Config = {
    val configDefaults = ConfigFactory.load(this.getClass().getClassLoader(), "wordcloud.conf")
    
    scala.sys.props.get("application.config") match {
      case Some(filename) => ConfigFactory.parseFile(new File(filename)).withFallback(configDefaults)
      case None => configDefaults
    }
  }
  
}