enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        // Vendored custom dependencies (formerly served from the now-offline repo.kryptonmc.org).
        // Keeps the build reproducible on CI and for anyone who clones the repo. See gradle/offline-repo/.
        maven {
            name = "offlineRepo"
            url = uri(rootDir.resolve("gradle/offline-repo"))
        }
        mavenCentral()
        maven("https://oss.sonatype.org/content/groups/public/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://jitpack.io")
    }
    versionCatalogs {
        create("global") {
            from(files("gradle/global.versions.toml"))
        }
    }
}

pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven("https://repo.spongepowered.org/repository/maven-public/")
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "krypton"

include("api")
include("server")
include("generators")
include("annotation-processor")
include("jar")

internalProject("annotations")
internalProject("ap")

pluginProject("bans")
pluginProject("whitelist")

fun internalProject(name: String) {
    include("internal-$name")
    project(":internal-$name").projectDir = file("internal/$name")
}

fun pluginProject(name: String) {
    include("$name-plugin")
    project(":$name-plugin").projectDir = file("plugins/$name")
}
