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
    val hprof = providers.gradleProperty("hprof")
    doFirst { args(hprof.orNull ?: throw GradleException("pass -Phprof=<heap dump>")) }
}

// Writes a demo MEMSCOPE_HOME with synthetic snapshots for browser checks: ./gradlew :analysis:demoHome -PdemoHome=<dir>
tasks.register<JavaExec>("demoHome") {
    group = "verification"
    description = "Writes a session of synthetic snapshots into -PdemoHome=<dir>."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.memscope.analysis.findings.FindingsDemoMainKt")
    val home = providers.gradleProperty("demoHome")
    doFirst { args(home.orNull ?: throw GradleException("pass -PdemoHome=<dir>")) }
}

// Times the findings of a dump: ./gradlew :analysis:perfFindings -Phprof=/path/to/dump.hprof
tasks.register<JavaExec>("perfFindings") {
    group = "verification"
    description = "Computes the findings of -Phprof=<file> with -Xmx6g, timing each rule and printing the peak heap."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.memscope.analysis.findings.FindingsPerfMainKt")
    maxHeapSize = providers.gradleProperty("perfXmx").getOrElse("6g")
    val hprof = providers.gradleProperty("hprof")
    doFirst { args(hprof.orNull ?: throw GradleException("pass -Phprof=<heap dump>")) }
}
