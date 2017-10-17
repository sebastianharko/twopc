logLevel := Level.Warn

addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.2.0")

addSbtPlugin("io.gatling" %% "gatling-sbt" % "2.2.0")

addSbtPlugin("com.lightbend.cinnamon" %% "sbt-cinnamon" % "2.5.2")

credentials += Credentials(Path.userHome / ".lightbend" / "commercial.credentials")

resolvers += Resolver.url("lightbend-commercial", url("https://repo.lightbend.com/commercial-releases"))(Resolver.ivyStylePatterns)
