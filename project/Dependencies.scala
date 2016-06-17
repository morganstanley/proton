import sbt._

object Version {
  final val Akka                     = "2.4.7"
  final val Scala                    = "2.11.8"
  final val Slf4J                    = "1.7.21"
  final val Logback                  = "1.1.7"
  final val ScalaLogging             = "3.4.0"
  final val TypesafeConfig           = "1.3.0"
  final val AkkaPersistenceCassandra = "0.6"
  final val AkkaPersistenceInMemory  = "1.2.14"
  final val AkkaKryo                 = "0.4.1"
  final val Constructr               = "0.13.2"
  final val Scopt                    = "3.4.0"
  final val Scaldi                   = "0.5.7"
  final val Csv                      = "0.1.11"
  final val ScalaTest                = "2.2.6"
  final val Slick                    = "3.1.1"
}

object Library {
  val typesafeConfig           = "com.typesafe"               %  "config"                               % Version.TypesafeConfig
  val slf4j                    = "org.slf4j"                  %  "slf4j-api"                            % Version.Slf4J
  val logback                  = "ch.qos.logback"             % "logback-classic"                       % Version.Logback
  val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"                        % Version.ScalaLogging
  val akkaActor                = "com.typesafe.akka"          %% "akka-actor"                           % Version.Akka
  val akkaSlf4j                = "com.typesafe.akka"          %% "akka-slf4j"                           % Version.Akka
  val akkaPersistence          = "com.typesafe.akka"          %% "akka-persistence"                     % Version.Akka
  val akkaCluster              = "com.typesafe.akka"          %% "akka-cluster"                         % Version.Akka
  val akkaClusterTools         = "com.typesafe.akka"          %% "akka-cluster-tools"                   % Version.Akka
  val akkaClusterSharding      = "com.typesafe.akka"          %% "akka-cluster-sharding"                % Version.Akka
  val akkaHttpCore             = "com.typesafe.akka"          %% "akka-http-core"                       % Version.Akka
  val akkaHttp                 = "com.typesafe.akka"          %% "akka-http-experimental"               % Version.Akka
  val akkaDistributedData      = "com.typesafe.akka"          %% "akka-distributed-data-experimental"   % Version.Akka
  val akkaHttpSprayJson        = "com.typesafe.akka"          %% "akka-http-spray-json-experimental"    % Version.Akka
  val akkaPersistenceCassandra = "com.github.krasserm"        %% "akka-persistence-cassandra"           % Version.AkkaPersistenceCassandra
  val akkaPersistenceInMemory  = "com.github.dnvriend"        %% "akka-persistence-inmemory"            % Version.AkkaPersistenceInMemory
  val akkaKryo                 = "com.github.romix.akka"      %% "akka-kryo-serialization"              % Version.AkkaKryo
  val constructrAkka           = "de.heikoseeberger"          %% "constructr-akka"                      % Version.Constructr
  val constructrEtcd           = "de.heikoseeberger"          %% "constructr-coordination-etcd"         % Version.Constructr
  val scopt                    = "com.github.scopt"           %% "scopt"                                % Version.Scopt
  val scaldi                   = "org.scaldi"                 %% "scaldi-akka"                          % Version.Scaldi
  val csv                      = "com.nrinaudo"               %% "kantan.csv"                           % Version.Csv
  val scalaTest                = "org.scalatest"              %% "scalatest"                            % Version.ScalaTest
  val akkaTestKit              = "com.typesafe.akka"          %% "akka-testkit"                         % Version.Akka
  val slick                    = "com.typesafe.slick"         %% "slick"                                % Version.Slick
}
