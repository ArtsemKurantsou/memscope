plugins {
    kotlin("jvm")
}

dependencies {
    implementation(libs.shark.graph)
    implementation(libs.shark.android)
    implementation(libs.kotlinx.serialization.json)
}

tasks.named<Test>("test") {
    // DominatorsTest.largeIrregularGraph (5M nodes, 20M edges) needs ~1 GB; leave headroom.
    maxHeapSize = "4g"
}
