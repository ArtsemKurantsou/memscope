plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":device"))
    testImplementation(project(":capture"))
    testImplementation(project(":analysis"))
    testImplementation(project(":server"))
}

dependencies {
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.server.netty)
}

// The server's Netty loads a native library; see server/build.gradle.kts.
tasks.withType<Test>().configureEach { jvmArgs("--enable-native-access=ALL-UNNAMED") }
