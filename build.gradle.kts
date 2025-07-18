import io.github.zenhelix.gradle.plugin.extension.PublishingType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
import pl.allegro.tech.build.axion.release.domain.PredefinedVersionCreator

plugins {
    signing
    `maven-publish`
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinKotlinxSerialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    alias(libs.plugins.axionRelease)
    alias(libs.plugins.mavenCentralPublish)
}

scmVersion {
    tag {
        prefix = "v"
    }
    nextVersion {
        suffix = "SNAPSHOT"
        separator = "-"
    }
    versionCreator = PredefinedVersionCreator.SIMPLE.versionCreator
}

group = "tel.schich"
version = scmVersion.version

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

val javadocJar by tasks.registering(Jar::class) {
    from(dokka.dokkaPublications.html.map { it.outputDirectory })
    dependsOn(tasks.dokkaGeneratePublicationHtml)
    archiveClassifier.set("javadoc")
}

publishing {
    repositories {
        maven {
            name = "mavenCentralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            credentials(PasswordCredentials::class)
        }
    }
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


val ci = System.getenv("CI") != null
if (!ci) {
    signing {
        useGpgCmd()
        sign(publishing.publications)
    }
}

mavenCentralPortal {
    credentials {
        username = project.provider { project.property("mavenCentralPortalUsername") as String }
        password = project.provider { project.property("mavenCentralPortalPassword") as String }
    }
    publishingType = PublishingType.AUTOMATIC
}