import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11

plugins {
    signing
    java
    `maven-publish`
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.dokka")
    id("io.github.gradle-nexus.publish-plugin")
    id("io.gitlab.arturbosch.detekt")
}

group = "tel.schich"
version = "3.0.2-SNAPSHOT"

val ktorVersion = "2.3.0"
val coroutinesVersion = "1.7.1"
val serializationVersion = "1.5.1"


tasks.withType<Test> {
    useJUnitPlatform()
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
            compilerOptions.configure {
                jvmTarget.set(JVM_11)
                freeCompilerArgs.add("-progressive")
            }
        }
    }
    js(IR) {
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
                api("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-websockets:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                implementation("io.github.microutils:kotlin-logging:3.0.4")
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
                implementation("io.ktor:ktor-client-java:$ktorVersion")
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
                implementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
                implementation("org.slf4j:slf4j-simple:2.0.6")
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
    this.repositories {
        sonatype()
    }
}
