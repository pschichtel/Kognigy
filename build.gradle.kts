import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    signing
    java
    `maven-publish`
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.atomicfu")
    id("org.jetbrains.dokka")
    id("io.github.gradle-nexus.publish-plugin")
    id("io.gitlab.arturbosch.detekt")
}

group = "tel.schich"
version = "3.0.0-SNAPSHOT"

val ktorVersion = "2.1.2"
val coroutinesVersion = "1.6.4"
val serializationVersion = "1.4.0"
val atomicfuVersion = "0.18.3"


tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-progressive")
    }
}

tasks.withType<Jar>().configureEach {
    metaInf.with(
        copySpec {
            from("${project.rootDir}/LICENSE")
        }
    )
}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of("11"))
}

kotlin {
    jvm {
        withJava()
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
    }
    js {
        browser {
            binaries.executable()
            testTask {
                enabled = false
            }
        }
        nodejs()
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-websockets:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("io.github.microutils:kotlin-logging:2.1.23")
                implementation("org.jetbrains.kotlinx:atomicfu:$atomicfuVersion")
            }
        }

        val commonTest by getting {
            dependsOn(commonMain)
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        getByName("jvmTest") {
            dependsOn(commonTest)
            dependencies {
                val junitVersion = "5.9.1"
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
                implementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
                implementation("org.slf4j:slf4j-simple:1.7.36")
            }
        }
    }
}

val javadocJar by tasks.creating(Jar::class) {
    from(tasks.dokkaHtml)
    dependsOn(tasks.dokkaHtml)
    archiveClassifier.set("javadoc")
}

publishing {
    publications {
        publications.withType<MavenPublication> {
            pom {
                name.set("Kognigy")
                description.set("A simple socket.io client for Cognigy.")
                url.set("https://github.com/pschichtel/kognigy")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("pschichtel")
                        name.set("Phillip Schichtel")
                        email.set("phillip@schich.tel")
                    }
                }
                scm {
                    url.set("https://github.com/pschichtel/kognigy")
                    connection.set("scm:git:https://github.com/pschichtel/kognigy")
                    developerConnection.set("scm:git:git@github.com:pschichtel/kognigy")
                }
            }
            artifact(javadocJar)
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications)
}

nexusPublishing {
    repositories {
        sonatype()
    }
}
