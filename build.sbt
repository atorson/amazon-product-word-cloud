
name := "Einstein_assignment"

version       := "0.0.1"

scalaVersion  := "2.11.8"

scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation", "-encoding", "utf8")


resolvers ++= Seq(
  "snapshots"           at "http://oss.sonatype.org/content/repositories/snapshots",
  "releases"            at "http://oss.sonatype.org/content/repositories/releases",
  "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
  "OSS Sonatype" at "https://repo1.maven.org/maven2/"
)

enablePlugins(JavaAppPackaging)

libraryDependencies ++= {
  val akkaV = "2.4.3"
  Seq(
    "com.typesafe.akka"   %%  "akka-http-core"    % akkaV,
    "com.typesafe.akka"   %%  "akka-http-testkit" % akkaV,
    "com.typesafe.akka"   %%  "akka-actor"    % akkaV,
    "com.typesafe.akka"   %%  "akka-stream" % akkaV,
    "net.debasishg" %% "redisreact" % "0.8",
    "com.typesafe.akka" %% "akka-stream-kafka" % "0.13",
    "org.apache.spark"    %%  "spark-core"        % "2.3.0",
    "org.apache.spark" %% "spark-streaming" % "2.3.0",
    "org.apache.spark" %% "spark-streaming-kafka-0-10" % "2.3.0",
    "com.github.swagger-akka-http" %% "swagger-akka-http" % "0.6.2",
    "net.ruippeixotog" %% "scala-scraper" % "1.2.0",
    "com.typesafe.akka"   %%  "akka-testkit"  % akkaV   % "test",
    "org.scalatest" %% "scalatest" % "2.2.1" % "test",
    "junit" % "junit" % "4.11" % "test",
    "com.typesafe" % "config" % "1.2.1",
    "com.typesafe.scala-logging" %% "scala-logging" % "3.4.0",
    "commons-codec" % "commons-codec" % "1.3"
  )
}.map(_.exclude("org.slf4j", "*"))

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.1.3"

test in assembly := {}
assemblyMergeStrategy in assembly := {
  case x if x.contains("org/slf4j/impl") => MergeStrategy.first
  //case x if x.contains("javax/annotation") => MergeStrategy.first
  case x if x.contains("javax") => MergeStrategy.first
  case x if x.contains("jpountz") => MergeStrategy.first
  case x if x.contains("apache") => MergeStrategy.first
  case x if x.contains("aopalliance") => MergeStrategy.first

  case x => {val oldStrategy = (assemblyMergeStrategy in assembly).value
            oldStrategy(x)}
}

fork := true
fork in (Test, run) := false