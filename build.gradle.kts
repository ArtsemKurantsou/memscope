import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

allprojects {
    group = "dev.memscope"
    version = "0.1.0"
    repositories { mavenCentral(); google() }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    dependencies {
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    // `test` runs without a device; tests tagged "emulator" run via `emulatorTest`.
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags("emulator") }
    }
    tasks.register<Test>("emulatorTest") {
        group = "verification"
        description = "Runs tests tagged 'emulator' (needs emulator-5554)."
        val test = tasks.named<Test>("test").get()
        testClassesDirs = test.testClassesDirs
        classpath = test.classpath
        useJUnitPlatform { includeTags("emulator") }
        outputs.upToDateWhen { false }
    }
}
