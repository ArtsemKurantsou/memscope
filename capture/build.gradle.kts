plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":device"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // StartupSessionEmulatorTest indexes the milestone dumps to check which planted blobs each one holds.
    testImplementation(project(":analysis"))
}

tasks.named<Test>("emulatorTest") {
    maxHeapSize = "4g"
}
