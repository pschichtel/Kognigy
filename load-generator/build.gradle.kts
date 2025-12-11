import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinKotlinxSerialization)
    alias(libs.plugins.shadow)
}

repositories {
    mavenCentral()
}

tasks.withType<Jar>().configureEach {
    manifest { attributes("Main-Class" to "tel.schich.kognigy.loadgenerator.MainKt") }
}

kotlin {
    jvmToolchain(25)
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                    freeCompilerArgs.add("-progressive")
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project.rootProject)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktorClientJava)
                implementation(libs.kotlinLogging)
                implementation(libs.logbackClassic)
            }
        }
    }
}