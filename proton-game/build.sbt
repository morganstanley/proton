name := "proton-game"

libraryDependencies ++= List(
  Library.typesafeConfig,
  Library.slf4j,
  Library.logback,
  Library.scalaLogging,
  Library.akkaActor,
  Library.akkaSlf4j,
  Library.akkaPersistence,
  Library.akkaCluster,
  Library.akkaClusterSharding,
  Library.akkaHttpCore,
  Library.akkaHttp,
  Library.akkaHttpSprayJson,
  Library.scopt,
  Library.scaldi,
  Library.akkaPersistenceInMemory  % "test",
  Library.scalaTest                % "test",
  Library.akkaTestKit              % "test"
)