pluginManagement {
    val publicationRepositoryPath =
        providers.gradleProperty("publicationRepository")
            .orNull
            ?: error("publicationRepositoryを指定してください。")
    val publicationVersion =
        providers.gradleProperty("publicationVersion")
            .orNull
            ?: error("publicationVersionを指定してください。")
    repositories {
        maven {
            url = uri(publicationRepositoryPath)
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.2.1"
        id("com.masaibar.datastore-inspector") version publicationVersion
    }
}

dependencyResolutionManagement {
    val publicationRepositoryPath =
        providers.gradleProperty("publicationRepository")
            .orNull
            ?: error("publicationRepositoryを指定してください。")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(publicationRepositoryPath)
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "datastore-inspector-publication-consumer"

include(":app")
