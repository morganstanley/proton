import sbt.{Tests, _}
import Keys._

object Common {
  val protonVersion = "1.0"

  val commonResolvers = Seq(
    "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven",
    "dnvriend at bintray" at "http://dl.bintray.com/dnvriend/maven",
    "hseeberger at bintray" at "http://dl.bintray.com/hseeberger/maven"
  )

  val settings: Seq[Def.Setting[_]] = Seq(
    version := protonVersion,
    scalaVersion := Version.Scala,
    resolvers := commonResolvers,
    organization := "proton",
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding", "UTF-8"
    ),
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-u",
      "target/test-reports")
  )
}