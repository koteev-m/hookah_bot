import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    application
}

application {
    mainClass.set("com.hookah.platform.backend.MainKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.aws.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.url.connection)
    implementation(libs.metadata.extractor)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.ktor.client.java)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
    implementation(libs.openpdf)
    implementation(libs.micrometer.registry.prometheus)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
        listOf("-Xjsr305=strict"),
    )
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    filter {
        include("**/src/main/kotlin/**")
        include("**/src/test/kotlin/**")
    }
}

// The OCI packaged version is distinct from Git revision and runtime APP_VERSION.
tasks.register("verifyImageVersion") {
    val packagedVersion = project.version.toString()
    val requestedVersion = providers.gradleProperty("imageVersion")
    doLast {
        check(requestedVersion.orNull == packagedVersion) {
            "Image version must equal the canonical Gradle project version: $packagedVersion"
        }
    }
}
