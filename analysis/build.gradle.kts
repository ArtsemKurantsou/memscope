plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.shark.graph)
    implementation(libs.shark.android)
    implementation(libs.kotlinx.serialization.json)
}

tasks.named<Test>("test") {
    // The 2,000,000-node deep-chain dominator test needs more than the default heap headroom.
    maxHeapSize = "2g"
}
