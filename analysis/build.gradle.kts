plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
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

// Performance check of the heap indexer on a large dump (see PERF.md):
// ./gradlew :analysis:perfIndex -Phprof=/path/to/dump.hprof
tasks.register<JavaExec>("perfIndex") {
    group = "verification"
    description = "Indexes -Phprof=<file> with -Xmx6g (override: -PperfXmx=3g) and prints timings and peak heap."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.memscope.analysis.PerfMainKt")
    maxHeapSize = providers.gradleProperty("perfXmx").getOrElse("6g")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    val hprof = providers.gradleProperty("hprof")
    doFirst { args(hprof.orNull ?: throw GradleException("pass -Phprof=<heap dump>")) }
}

// Performance check of the snapshot queries (see PERF.md):
// ./gradlew :analysis:perfSnapshot -Phprof=/path/to/dump.hprof
tasks.register<JavaExec>("perfSnapshot") {
    group = "verification"
    description = "Opens -Phprof=<file> as a Snapshot with -Xmx6g and times the first tree, class and details queries."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.memscope.analysis.SnapshotPerfMainKt")
    maxHeapSize = providers.gradleProperty("perfXmx").getOrElse("6g")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    val hprof = providers.gradleProperty("hprof")
    doFirst { args(hprof.orNull ?: throw GradleException("pass -Phprof=<heap dump>")) }
}
