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
