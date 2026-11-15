plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":device"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
