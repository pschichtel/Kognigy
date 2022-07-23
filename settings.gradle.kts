
rootProject.name = "kognigy"

pluginManagement {

    val kotlinVersion: String by settings
    val dokkaVersion: String by settings
    val nexusPublishingVersion: String by settings
    val detektVersion: String by settings

    plugins {
        kotlin("multiplatform") version(kotlinVersion)
        kotlin("plugin.serialization") version(kotlinVersion)
        kotlin("plugin.atomicfu") version(kotlinVersion)
        id("org.jetbrains.dokka") version(dokkaVersion)
        id("io.github.gradle-nexus.publish-plugin") version(nexusPublishingVersion)
        id("io.gitlab.arturbosch.detekt") version(detektVersion)
    }
}
