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
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.json)
    // Ktor logs through SLF4J; without a binding it prints a warning on every start.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.20")
    testImplementation(libs.ktor.server.tests)
}

// Route tests build small real heap dumps with the analysis tests' synthetic writers (DiffHprof).
evaluationDependsOn(":analysis")
val analysisTestOutput = rootProject.project(":analysis").extensions.getByType<SourceSetContainer>()["test"].output
dependencies {
    testImplementation(analysisTestOutput)
    testImplementation(libs.shark.graph)
}

// Netty loads a native library; JDK 24+ warns (and later JDKs will refuse) unless native access is enabled.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"
application { applicationDefaultJvmArgs = listOf(nativeAccess) }
tasks.withType<Test>().configureEach { jvmArgs(nativeAccess) }
