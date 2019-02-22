import org.gradle.tooling.GradleConnector
import java.util.concurrent.*

plugins {
    java
    application
    kotlin("jvm") version "1.3.20"
    id("kotlinx-serialization") version "1.3.20"
    id("com.google.cloud.tools.jib") version "1.0.0"
    id("com.github.johnrengelman.shadow") version "4.0.4"
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://kotlin.bintray.com/kotlinx")
}

dependencies {
    compile("io.ktor:ktor-server-core:1.1.3")
    compile("io.ktor:ktor-server-netty:1.1.3")
    compile("io.ktor:ktor-freemarker:1.1.3")
    compile("io.ktor:ktor-client-cio:1.1.3")
    compile("io.ktor:ktor-client-json-jvm:1.1.3")
    compile("ch.qos.logback:logback-classic:1.2.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.compileKotlin {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClassName = "WebAppKt"
}

jib {
    val projectId: String? by project

    from.image = "launcher.gcr.io/google/openjdk8"
    to.image = "gcr.io/$projectId/where-am-i"
    container {
        mainClass = application.mainClassName
        ports = listOf("8080")
    }
}

tasks.create("stage") {
    dependsOn("shadowJar")
}


// one task that does both the continuous compile and the run

tasks.create("dev") {
    doLast {
        fun fork(task: String, vararg args: String): Future<*> {
            return Executors.newSingleThreadExecutor().submit {
                GradleConnector.newConnector()
                               .forProjectDirectory(project.projectDir)
                               .connect()
                               .use {
                                   it.newBuild()
                                     .addArguments(*args)
                                     .setStandardError(System.err)
                                     .setStandardInput(System.`in`)
                                     .setStandardOutput(System.out)
                                     .forTasks(task)
                                     .run()
                               }
            }
        }

        val classesFuture = fork("classes", "-t")
        val runFuture = fork("run")

        classesFuture.get()
        runFuture.get()
    }
}

defaultTasks("dev")
