name := "proton-game-hermes"

libraryDependencies ++= List(
  Library.akkaKryo,
  Library.akkaClusterTools,
  Library.akkaPersistenceInMemory,
  Library.akkaPersistenceCassandra,
  Library.akkaDistributedData,
  Library.constructrAkka,
  Library.constructrEtcd,
  Library.csv
)

mainClass in Compile := Some("proton.game.hermes.HermesProtonApp")

dockerfile in docker := {
  val appDir: File = stage.value
  val targetDir = "/app"

  new Dockerfile {
    from("java:openjdk-8-jre")
    copy(appDir, targetDir)
    expose(2551)
    expose(8080)
    expose(10001)
    entryPoint(s"$targetDir/bin/${executableScriptName.value}")
  }
}

imageNames in docker := Seq(
  ImageName(s"${organization.value}/${name.value}:latest"),

  ImageName(
    namespace = Some(organization.value),
    repository = name.value,
    tag = Some("v" + version.value)
  )
)