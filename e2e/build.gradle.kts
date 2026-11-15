plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":device"))
    testImplementation(project(":capture"))
    testImplementation(project(":analysis"))
    testImplementation(project(":server"))
}
