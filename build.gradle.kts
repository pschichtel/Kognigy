import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11

plugins {
    signing
    `maven-publish`
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinKotlinxSerialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.nexusPublish)
    alias(libs.plugins.detekt)
}

group = "tel.schich"
version = "4.0.0"

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

kotlin {
    jvmToolchain(11)
    jvm {
        withJava()
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JVM_11)
                    freeCompilerArgs.add("-progressive")
                }
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
                implementation(project.dependencies.platform(libs.ktorBom))
                implementation(project.dependencies.platform(libs.kotlinxCoroutinesBom))
                implementation(project.dependencies.platform(libs.kotlinxSerializationBom))
                implementation(libs.parserKombinator)
                api(libs.ktorClientCore)
                api(libs.ktorClientWebsockets)
                api(libs.kotlinxCoroutinesCore)
                api(libs.kotlinxSerializationJson)
                implementation(libs.kotlinLogging)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.ktorClientJava)
                implementation(libs.logbackClassic)
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
        }
        publications.named("jvm", MavenPublication::class) {
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
