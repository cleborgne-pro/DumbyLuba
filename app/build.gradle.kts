import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}
// Optional release signing: keystore.properties (not versioned) at the repository root, see
// keystore.properties.example. Without this file, the release variant is built unsigned.
val releaseSigning = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}
android {
    namespace = "fr.luba.poc"
    compileSdk = 36
    defaultConfig {
        applicationId = "fr.luba.poc"
        minSdk = 26
        targetSdk = 36
        versionCode = 187
        versionName = "1.17.0"
        testInstrumentationRunner = "fr.luba.poc.planning.PlannerDeviceBenchmark"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
        // Same application and signature, without the ART debugging constraints.
        // No check removal and no minification in this variant.
        create("fold") {
            initWith(getByName("debug"))
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    // Compose screenshots on the JVM (Robolectric): visual check without a device.
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// Published release (GitHub): its own permanent identifier, so it installs next to the development builds
// (debug and fold: fr.luba.poc, debug key) without replacing them. Changing it, or losing the release key, would
// force users to uninstall and lose their data. Code and resources keep the fr.luba.poc namespace.
androidComponents {
    onVariants(selector().withBuildType("release")) { it.applicationId.set("io.github.cleborgne_pro.dumbyluba") }
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks { all().configureEach { builtins { create("java") } } }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    // Gateway pairing: QR code drawn on the gateway, read by the pilot's camera.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.46.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.46.1")
    testImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Fast tests are for iterations; deployment keeps the full suite.
// Planner sweeps on the reference map (every zone, angle or spacing): about 80 % of the test time, excluded by
// -PlubaQuickTests, run on their own by tools/check.sh planner and kept in the full suite.
val plannerSweeps = listOf("**/ReferenceMapPlannerTest*", "**/ReferenceMapPlannerZamboniSpiralTest*", "**/ReferenceTransitTest*",
    "**/ReferenceSimulationTest*", "**/ZamboniQaTest*", "**/ZamboniQaSpacingTest*", "**/ZamboniManualAngleReproTest*", "**/ZamboniReferenceTest*", "**/ZamboniOrderPropertyTest*",
    "**/OrientationAcceptanceTest*", "**/OrientationReferenceTest*", "**/SpiralReferenceTest*", "**/SpiralDirectionTest*",
    "**/StartTransitTest*", "**/TurnStyleReferenceTest*")
// Test slots shared by every worktree of the machine: sessions that ran their full suite at the same time loaded
// 10 cores up to 43 and made each run two to four times slower (30 to 60 minutes instead of about 19). A test task waits for a
// free slot (lock files in /tmp/dumbyluba-test-slots, released at the end of the build, or by the system if Gradle
// dies). One slot by default: a single suite already fills the 10 cores; -PtestSlots=N changes it.
abstract class TestSlots : BuildService<TestSlots.Params>, AutoCloseable {
    interface Params : BuildServiceParameters { val slots: Property<Int> }
    private var held: Pair<FileChannel, FileLock>? = null
    @Synchronized fun acquire(log: org.gradle.api.logging.Logger) {
        if (held != null) return
        val dir = File("/tmp/dumbyluba-test-slots").apply { mkdirs() }
        var warned = false
        while (true) {
            for (i in 0 until parameters.slots.get()) {
                val channel = RandomAccessFile(File(dir, "slot-$i.lock"), "rw").channel
                val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) { held = channel to lock; return }
                channel.close()
            }
            if (!warned) { log.lifecycle("Waiting for a free test slot (other sessions are running their tests)…"); warned = true }
            Thread.sleep(5_000)
        }
    }
    override fun close() { held?.let { (channel, lock) -> lock.release(); channel.close() }; held = null }
}
val testSlots = gradle.sharedServices.registerIfAbsent("dumbylubaTestSlots", TestSlots::class) {
    parameters.slots.set(providers.gradleProperty("testSlots").orNull?.toInt() ?: 1)
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Two processes: the route preparations of each one already use up to 8 threads (PreparationPool), so the
    // 10 cores are full. Measured on 2026-10-29: 5 processes, with or without capping the pool, made the long
    // sweep classes (reference map, Zamboni) up to three times slower and the full run longer (28 to 31 minutes).
    // Without the sweeps (-PlubaQuickTests), four: the longest classes there wait in real time (gateway sockets,
    // robot bench), so 2 / 3 / 4 / 5 processes took 4:23 / 3:53 / 2:58 / 3:30 (measured on 2026-10-29).
    val quick = providers.gradleProperty("lubaQuickTests").isPresent
    maxParallelForks = providers.gradleProperty("testForks").orNull?.toInt() ?: if (quick) 4 else 2
    // 1 GB per fork instead of 512 MB (fewer G1 cycles on the large sweeps).
    maxHeapSize = "1g"
    usesService(testSlots)
    doFirst { testSlots.get().acquire(logger) }
    providers.gradleProperty("plannerBenchmarkOutput").orNull?.let { systemProperty("plannerBenchmarkOutput", it) }
    providers.gradleProperty("plannerBenchmarkStrategies").orNull?.let { systemProperty("plannerBenchmarkStrategies", it) }
    providers.gradleProperty("plannerBenchmarkIndexed").orNull?.let { systemProperty("plannerBenchmarkIndexed", it) }
    providers.gradleProperty("plannerBenchmarkCases").orNull?.let { systemProperty("plannerBenchmarkCases", it) }
    providers.gradleProperty("plannerBenchmarkReference").orNull?.let { systemProperty("plannerBenchmarkReference", it) }
    providers.gradleProperty("plannerBenchmarkRepeats").orNull?.let { systemProperty("plannerBenchmarkRepeats", it) }
    providers.gradleProperty("plannerBenchmarkWarmups").orNull?.let { systemProperty("plannerBenchmarkWarmups", it) }
    providers.gradleProperty("plannerBenchmarkProduction").orNull?.let { systemProperty("plannerBenchmarkProduction", it) }
    for(name in listOf("plannerBenchmarkPreviousProduction","plannerBenchmarkCandidateBudget","plannerBenchmarkLocalSearchBudget","plannerBenchmarkInteractiveReference","plannerBenchmarkStation","followerRouteOutput"))
        providers.gradleProperty(name).orNull?.let { systemProperty(name,it) }
    for(name in listOf("pipelineProfile","pipelineProfileZones","pipelineProfileRepeats","pipelineProfileTogether",
        "executionProfile","executionProfileZones","executionProfileSpeeds","executionProfileRobots","executionProfileVerbose","executionProfileSeeds","executionProfileWarm","traceEdges","traceDelay","traceDegs","traceSeed","traceFrom","traceHeadingError","traceZone","traceSpeed","sweepBarProbe","sweepBarZones","sweepBarModes","sweepBarRender"))
        providers.gradleProperty(name).orNull?.let { systemProperty(name,it) }
    providers.gradleProperty("pipelineProfileJfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=${providers.gradleProperty("pipelineProfileJfc").orNull ?: "profile"}") }
    // Interface language of unit tests: French (existing assertions), English for documentation screenshots;
    // -PtestLang=en|fr forces it (English smoke run: only non-assertion failures matter).
    systemProperty("dumbyluba.lang", providers.gradleProperty("testLang").orNull
        ?: if (providers.gradleProperty("uiShots").isPresent) "en" else "fr")
    // Opt-in screenshots: -PuiShots=output/directory
    providers.gradleProperty("uiShots").orNull?.let { systemProperty("uiShots", it); systemProperty("robolectric.graphicsMode", "NATIVE"); systemProperty("roborazzi.test.record", "true") }
    if (quick) exclude(plannerSweeps)
    if (providers.gradleProperty("lubaPlannerSweeps").isPresent) include(plannerSweeps)
}

// ART safeguard: a method with more than ~256 Dalvik registers (large Compose function) was
// miscompiled by D8 in the non-debuggable variant, causing a VerifyError when opening the map.
val checkFoldDexRegisters by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuse l'APK fold si une méthode de l'app dépasse 224 registres Dalvik."
    val apk = layout.buildDirectory.file("outputs/apk/fold/app-fold.apk")
    val script = rootProject.file("tools/check-dex-registers.py")
    inputs.file(apk)
    inputs.file(script)
    // Up to date while the APK and the script are unchanged: no new read of the same APK at each build.
    val checked = layout.buildDirectory.file("reports/dex-registers/checked.txt")
    outputs.file(checked)
    commandLine("python3", script.absolutePath, apk.get().asFile.absolutePath, "224")
    doLast { checked.get().asFile.writeText("APK checked\n") }
}
afterEvaluate {
    checkFoldDexRegisters.configure { dependsOn("packageFold") }
    tasks.named("assembleFold") { finalizedBy(checkFoldDexRegisters) }
    tasks.named("installFold") { dependsOn(checkFoldDexRegisters) }
}

// JVM test coverage, opt-in: ./gradlew :app:testDebugUnitTest :app:lubaCoverageReport -PlubaCoverage
// JaCoCo agent at runtime (rather than AGP instrumentation) so Robolectric tests, whose sandbox reloads
// classes, are counted too. Report: app/build/reports/jacoco/lubaCoverageReport/ (HTML and XML).
if (providers.gradleProperty("lubaCoverage").isPresent) {
    apply(plugin = "jacoco")
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        extensions.configure<JacocoTaskExtension> { isIncludeNoLocationClasses = true; excludes = listOf("jdk.internal.*") }
    }
    tasks.register<JacocoReport>("lubaCoverageReport") {
        group = "verification"
        description = "Rapport de couverture des tests JVM debug (Robolectric compris)."
        val test = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
        executionData(test.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! })
        classDirectories.setFrom(
            fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")),
            fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) { exclude("**/R.class", "**/R$*.class", "**/BuildConfig.class") },
        )
        sourceDirectories.setFrom(files("src/main/java", layout.buildDirectory.dir("generated/source/proto/debug/java")))
        reports { xml.required.set(true); html.required.set(true) }
    }
}
