pluginManagement {
  includeBuild("gradle-plugin")

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

rootProject.name = "datastore-inspector-sdk"

include(
  ":protocol",
  ":runtime-core",
  ":runtime-preferences",
  ":runtime-shared-preferences",
  ":runtime-protobuf",
  ":sample-app"
)
