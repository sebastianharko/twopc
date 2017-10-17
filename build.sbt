name := "2pc"

version := "2.5"

scalaVersion := "2.12.3"

// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")


libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % "2.5.6"

libraryDependencies += "com.lightbend.akka" %% "akka-management-cluster-http" % "0.4"

libraryDependencies += "com.typesafe.akka" %% "akka-persistence" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-cluster-sharding" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.10"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.56"

libraryDependencies += "de.heikoseeberger" %% "akka-http-json4s" % "1.19.0-M2"

libraryDependencies += "org.json4s" %% "json4s-jackson" % "3.5.3"

libraryDependencies ++= Vector(
  "de.heikoseeberger" %% "constructr" % "0.17.0",
  "de.heikoseeberger" %% "constructr-coordination-etcd" % "0.17.0"
)

libraryDependencies += "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.3.0" % "test"

libraryDependencies += "io.gatling" % "gatling-test-framework"  % "2.3.0" % "test"

fork := true

parallelExecution in Test := false

enablePlugins(JavaAppPackaging)

enablePlugins(GatlingPlugin)

dockerEntrypoint ++= Seq(
  "-XX:+UnlockExperimentalVMOptions",
  "-XX:+UseCGroupMemoryLimitForHeap",
  "-XX:MaxRAMFraction=1",
  "-XshowSettings:vm",
  "-XX:+UseG1GC",
  "-XX:+AggressiveOpts"
)







