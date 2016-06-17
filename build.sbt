name := "proton"

lazy val root = (project in file("."))
  .settings(Common.settings: _*)
  .aggregate(game, hermes, users)

lazy val game = (project in file("proton-game"))
  .settings(Common.settings: _*)

lazy val hermes = (project in file("proton-game-hermes"))
  .dependsOn(game)
  .settings(Common.settings: _*)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)

lazy val users = (project in file("proton-users"))
  .settings(Common.settings: _*)