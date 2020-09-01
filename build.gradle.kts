import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    kotlin("jvm") version "1.4.0"
    kotlin("kapt") version "1.4.0"
    kotlin("plugin.allopen") version "1.4.0"
    id("com.google.cloud.tools.jib") version "2.5.0"
}

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7")

    implementation("io.micronaut:micronaut-runtime:2.0.1")
    implementation("io.micronaut:micronaut-http-client:2.0.1")
    implementation("io.micronaut:micronaut-http-server-netty:2.0.1")
    implementation("io.micronaut.views:micronaut-views-thymeleaf:2.0.0")

    runtime("ch.qos.logback:logback-classic:1.2.3")

    runtime("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.7")

    kapt("io.micronaut:micronaut-inject-java:2.0.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test:1.4.0")
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:2.0.1")
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:2.0.1")

    kaptTest("io.micronaut:micronaut-inject-java:2.0.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
        javaParameters = true
    }
}

application {
    mainClassName = "whereami.WebAppKt"
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("spek2")
    }
    testLogging {
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
        events(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }
}

allOpen {
    annotation("io.micronaut.aop.Around")
}

kapt {
    arguments {
        arg("micronaut.processing.incremental", true)
        arg("micronaut.processing.annotations", "whereami.*")
        arg("micronaut.processing.group", "whereami")
        arg("micronaut.processing.module", "whereami")
    }
}

tasks.withType<JavaExec> {
    jvmArgs = listOf("-XX:TieredStopAtLevel=1", "-Dcom.sun.management.jmxremote")

    if (gradle.startParameter.isContinuous) {
        systemProperties = mapOf(
                "micronaut.io.watch.restart" to "true",
                "micronaut.io.watch.enabled" to "true",
                "micronaut.io.watch.paths" to "src/main"
        )
    }
}

jib {
    container {
        mainClass = application.mainClassName
        ports = listOf("8080")
    }
}
