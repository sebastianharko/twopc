name := "2pc"

version := "6.9"

scalaVersion := "2.12.3"

lazy val bank = project in file(".") enablePlugins (Cinnamon)

// Add the Monitoring Agent for run and test
cinnamon in run := true

cinnamon in test := false

// All releases including intermediate ones are published here,
// final ones are also published to Maven Central.
resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies += "com.typesafe.akka" %% "akka-persistence" % "2.5.0"

libraryDependencies += "com.typesafe.akka" %% "akka-cluster-sharding" % "2.5.0"

libraryDependencies += "com.typesafe.akka" %% "akka-testkit" % "2.5.0" % "test"

libraryDependencies += "com.typesafe.akka" %% "akka-slf4j" % "2.5.0"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.1"

libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test"

libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.3"

libraryDependencies += "com.typesafe.akka" %% "akka-persistence-cassandra" % "0.58"

libraryDependencies += "com.lightbend.akka" %% "akka-diagnostics" % "1.0.3"

// SBR
libraryDependencies += "com.lightbend.akka" %% "akka-split-brain-resolver" % "1.0.3"


// Cinnamon
libraryDependencies += Cinnamon.library.cinnamonOpenTracingJaeger

// Use Coda Hale Metrics
libraryDependencies += Cinnamon.library.cinnamonCHMetricsStatsDReporter
libraryDependencies += Cinnamon.library.cinnamonCHMetrics
// Use Akka instrumentation
libraryDependencies += Cinnamon.library.cinnamonAkka

libraryDependencies += "com.lightbend.cinnamon" %% "cinnamon-chmetrics-jvm-metrics" % "2.5.2"

// ConstructR
libraryDependencies ++= Vector(
  "de.heikoseeberger" %% "constructr" % "0.17.0",
  "de.heikoseeberger" %% "constructr-coordination-etcd" % "0.17.0"
)

fork := true

parallelExecution in Test := false

enablePlugins(JavaAppPackaging)

dockerEntrypoint ++= Seq(
  "-J-XX:+UnlockExperimentalVMOptions",
  "-J-XX:+UseCGroupMemoryLimitForHeap",
  "-J-XX:MaxRAMFraction=1",
  "-J-XshowSettings:vm",
  "-J-XX:+UseG1GC",
  "-J-XX:+AggressiveOpts",
  "-J-XX:+PrintFlagsFinal",
  "-J-Djava.net.preferIPv4Stack=true"
)

// Protocol Buffers
PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)

// (optional) If you need scalapb/scalapb.proto or anything from
// google/protobuf/*.proto
libraryDependencies += "com.trueaccord.scalapb" %% "scalapb-runtime" % com.trueaccord.scalapb.compiler.Version.scalapbVersion % "protobuf"


// Http4s
val Http4sVersion = "0.17.5"

libraryDependencies ++= Seq(
  "org.http4s"     %% "http4s-blaze-server" % Http4sVersion,
  "org.http4s"     %% "http4s-dsl"          % Http4sVersion
)
