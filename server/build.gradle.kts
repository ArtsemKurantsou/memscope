plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.memscope.server.MainKt")
}

dependencies {
    implementation(project(":device"))
    implementation(project(":capture"))
    implementation(project(":analysis"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    // Ktor logs through SLF4J; without a binding it prints a warning on every start.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
    testImplementation(libs.ktor.server.tests)
}
