logLevel := Level.Warn

credentials += Credentials(Path.userHome / ".lightbend" / "commercial.credentials")

resolvers += Resolver.url("lightbend-commercial", url("https://repo.lightbend.com/commercial-releases"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.2.0")

addSbtPlugin("com.lightbend.cinnamon" %% "sbt-cinnamon" % "2.5.3")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")
