pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
plugins {
    // Provision the x64 JDK 21 toolchain when no local installation matches.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositories {
        google { content { includeGroupByRegex("androidx\\..*"); includeGroup("com.android.tools.build") } }
        mavenCentral()
    }
}
rootProject.name = "LimingjieDesktop"
include(":shared", ":device", ":ocr", ":platform", ":app")
