import io.github.zenhelix.gradle.plugin.extension.PublishingType
import io.github.zenhelix.gradle.plugin.task.PublishBundleMavenCentralTask
import org.gradle.kotlin.dsl.withType
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
private val signingKey = System.getenv("SIGNING_KEY")?.ifBlank { null }?.trim()
private val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.ifBlank { null }?.trim() ?: ""

when {
    signingKey != null -> {
        logger.lifecycle("Received a signing key, using in-memory pgp keys!")
        signing {
            useInMemoryPgpKeys(signingKey, signingKeyPassword)
            sign(publishing.publications)
        }
    }
    !ci -> {
        logger.lifecycle("Not running in CI, using the gpg command!")
        signing {
            useGpgCmd()
            sign(publishing.publications)
        }
    }
    else -> {
        logger.lifecycle("Not signing artifacts!")
    }
}

private fun Project.getSecret(name: String): Provider<String> = provider {
    val env = System.getenv(name)
        ?.ifBlank { null }
    if (env != null) {
        return@provider env
    }

    val propName = name.split("_")
        .map { it.lowercase() }
        .joinToString(separator = "") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
        .replaceFirstChar { it.lowercase() }

    property(propName) as String
}

mavenCentralPortal {
    credentials {
        username = project.getSecret("MAVEN_CENTRAL_PORTAL_USERNAME")
        password = project.getSecret("MAVEN_CENTRAL_PORTAL_PASSWORD")
    }
    publishingType = PublishingType.AUTOMATIC
}

val mavenCentralDeploy by tasks.registering(DefaultTask::class) {
    group = "publishing"
    val isSnapshot = project.version.toString().endsWith("-SNAPSHOT")

    if (isSnapshot) {
        logger.lifecycle("Snapshot deployment!")
        for (project in allprojects) {
            val tasks = project.tasks
                .withType<PublishToMavenRepository>()
                .matching { it.repository.name == "mavenCentralSnapshots" }
            dependsOn(tasks)
        }
    } else {
        logger.lifecycle("Release deployment!")
        for (project in allprojects) {
            val tasks = project.tasks
                .withType<PublishBundleMavenCentralTask>()
            dependsOn(tasks)
        }
    }
}

val githubActions by tasks.registering(DefaultTask::class) {
    group = "publishing"
    val deployRefPattern = """^refs/(?:tags/v\d+.\d+.\d+.\d+|heads/main)$""".toRegex()
    val ref = System.getenv("GITHUB_REF")?.ifBlank { null }?.trim()

    if (ref != null && deployRefPattern.matches(ref)) {
        logger.lifecycle("Job in $ref will deploy!")
        dependsOn(mavenCentralDeploy)
    } else {
        logger.lifecycle("Job will only build!")
        dependsOn(tasks.assemble)
    }
}