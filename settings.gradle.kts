rootProject.name = "kognigy"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("load-generator")
project(":load-generator").name = "kognigy-load-generator"