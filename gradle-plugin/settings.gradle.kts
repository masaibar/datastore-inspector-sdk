import java.util.Properties

pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

val artifactCoordinatesFile =
  file("../gradle/artifact-coordinates.properties")
val artifactCoordinates =
  Properties().apply {
    check(artifactCoordinatesFile.isFile) {
      "artifact座標の正本がありません: $artifactCoordinatesFile"
    }
    artifactCoordinatesFile.inputStream().use(::load)
  }
rootProject.name =
  artifactCoordinates.getProperty("gradlePluginArtifact")
    ?.takeIf(String::isNotBlank)
    ?: error("artifact座標の正本にgradlePluginArtifactがありません。")
