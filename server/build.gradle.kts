plugins {
    kotlin("jvm")
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
    implementation(libs.ktor.server.sse)
    testImplementation(libs.ktor.server.tests)
}
