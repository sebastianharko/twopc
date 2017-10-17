name := "2pc"

version := "2.5"

scalaVersion := "2.12.3"

lazy val helloAkka = project in file(".") enablePlugins (Cinnamon)

// Add the Monitoring Agent for run and test
cinnamon in run := true

cinnamon in test := false

// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")


libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-persistence" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-cluster-sharding" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.10"

libraryDependencies += "com.typesafe.akka" %% "akka-http-core" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.6"

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.5.6"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.58"

libraryDependencies += "de.heikoseeberger" %% "akka-http-json4s" % "1.19.0-M2"

libraryDependencies += "org.json4s" %% "json4s-jackson" % "3.5.3"

// Use Coda Hale Metrics
libraryDependencies += Cinnamon.library.cinnamonCHMetricsStatsDReporter
libraryDependencies += Cinnamon.library.cinnamonCHMetrics
// Use Akka instrumentation
libraryDependencies += Cinnamon.library.cinnamonAkka
libraryDependencies += Cinnamon.library.cinnamonAkkaHttp
libraryDependencies += "com.lightbend.cinnamon" %% "cinnamon-chmetrics-jvm-metrics" % "2.5.2"

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







