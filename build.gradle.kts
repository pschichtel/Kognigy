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
version = "1.3.1-SNAPSHOT"


tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-progressive")
    }
}

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        withJava()
    }
    js {
        browser {
            binaries.executable()
        }
        nodejs()
    }
    sourceSets {
        val commonMain by getting {

            dependencies {
                val ktorVersion = "2.0.3"
                val coroutinesVersion = "1.6.4"
                val serializationVersion = "1.3.3"
                val atomicfuVersion = "0.18.3"
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-websockets:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
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

        val jvmTest by getting {
            dependsOn(commonTest)
            dependencies {
                val ktorVersion = "2.0.3"
                val junitVersion = "5.8.2"
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
                implementation("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
            }
        }
    }
}

//val sourcesJar by tasks.creating(Jar::class) {
//    dependsOn(JavaPlugin.CLASSES_TASK_NAME)
//    archiveClassifier.set("sources")
//    from(sourceSets["main"].allSource)
//}

val javadocJar by tasks.creating(Jar::class) {
    dependsOn(tasks.dokkaJavadoc)
    archiveClassifier.set("javadoc")
    from(tasks.dokkaJavadoc)
}

fun isSnapshot() = version.toString().endsWith("-SNAPSHOT")

publishing {
    repositories {
        maven {
            name = "cubyte"
            url = uri("https://maven.cubyte.org/repository/${if (isSnapshot()) "snapshots" else "releases"}/")
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            //artifact(sourcesJar)
            artifact(javadocJar)
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
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
}

nexusPublishing {
    repositories {
        sonatype()
    }
}
