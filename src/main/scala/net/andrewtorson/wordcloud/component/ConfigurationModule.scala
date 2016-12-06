/*
 * Copyright (c) 2016 Omron Adept Technologies. All rights reserved
 * Author: Andrew Torson
 * Date: Aug 8, 2016
 */

package net.andrewtorson.wordcloud.component

import java.io.File
import java.net.InetSocketAddress

import scala.util.Try

import com.typesafe.config.{Config, ConfigFactory}

trait ConfigurationModule {
  val config: Config

  def getAddress(path: String): Try[InetSocketAddress] = {
    Try{val x = config.getConfig(path).getString("address").split(":")
        new InetSocketAddress(x(0), x(1).toInt)}
  }
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