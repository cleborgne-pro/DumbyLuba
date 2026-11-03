import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.ktlint)
}

// Style: ktlint with a reduced, high-signal rule set (see .editorconfig): existing code passes it as is, so no
// multi-megabyte baseline is needed and new code is checked from the first line.
ktlint {
    version.set(libs.versions.ktlint.cli.get())
    filter { exclude { it.file.path.contains("generated") } }
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
        versionCode = 189
        versionName = "1.19.0"
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
            // Shrinking and resource removal on the published APK (unminified release: 19 MB, 60 MB of dex on
            // 2026-10-30). Keep rules in proguard-rules.pro; the Dalvik register check and the deploy verification
            // stay the guard rails against a D8/ART regression.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // Lint on the app sources only: the analysis of the unit test sources (lintAnalyzeFoldUnitTest, 1 min 14 s of
    // CPU at each deployment, competing with the tests) reported nothing that the tests do not already check.
    // No lint warning on the app (2026-10-31): a new one fails the check. A deliberate case is justified where it is
    // (tools:ignore, @SuppressLint) or in app/lint.xml.
    lint { ignoreTestSources = true; warningsAsErrors = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
// Published release (GitHub): its own permanent identifier, so it installs next to the development builds
// (debug and fold: fr.luba.poc, debug key) without replacing them. Changing it, or losing the release key, would
// force users to uninstall and lose their data. Code and resources keep the fr.luba.poc namespace.
androidComponents {
    onVariants(selector().withBuildType("release")) { it.applicationId.set("io.github.cleborgne_pro.dumbyluba") }
}
// Compose stability: the lists and maps held by the UI state are immutable by contract (replaced, never mutated
// after being published). Declaring them stable lets screens skip recomposition instead of re-running the whole
// tree at each telemetry frame (1 Hz idle, 5 Hz while a route is tracked).
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_compiler_config.conf"))
}
protobuf {
    // Same version as `protobuf` in gradle/libs.versions.toml (protobuf-java); the catalog accessor is not usable in
    // this provider-typed plugin block (script compilation failed on a clean configuration).
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks { all().configureEach { builtins { create("java") } } }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.protobuf.java)
    implementation(libs.jts.core)
    // Gateway pairing: QR code drawn on the gateway, read by the pilot's camera.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Planner sweeps on the reference map (every zone, angle or spacing): about 70 % of the test time. They never run in
// test<Variant>UnitTest but in their own test<Variant>PlannerSweeps task (registered below): tools/check.sh planner runs
// them alone, the full suite (tools/check.sh full, deployments that touch the planner, publication) runs both tasks.
// The second group: reference-map tests that only use the map, planning and simulation packages and took 3.5 s or
// more each, 170 s of the 600 s of quick test classes (2026-10-31). A branch that does not touch those packages
// cannot change their result; tools/deploy.sh runs them, with the sweeps, as soon as it does.
val plannerSweeps = listOf("**/ReferenceMapPlannerTest*", "**/ReferenceMapPlannerZamboniSpiralTest*", "**/ReferenceTransitTest*",
    "**/ReferenceSimulationTest*", "**/ZamboniQaTest*", "**/ZamboniQaSpacingTest*", "**/ZamboniManualAngleReproTest*", "**/ZamboniReferenceTest*", "**/ZamboniOrderPropertyTest*",
    "**/OrientationAcceptanceTest*", "**/OrientationReferenceTest*", "**/SpiralReferenceTest*", "**/SpiralDirectionTest*", "**/NarrowPassageReferenceTest*",
    "**/StartTransitTest*", "**/TurnStyleReferenceTest*", "**/OuterOffsetCoverageTest*",
    "**/FastPipelineTest*", "**/OptimizedModeTest*", "**/DockObstaclePreviewTest*", "**/ZamboniDeadEndTest*", "**/SpiralCentreResumeTest*",
    "**/ObstacleBypassTest*", "**/BorderMarginChassisTest*", "**/ReturnReserveTest*", "**/SmartResumeReferenceTest*", "**/BorderClearanceTest*",
    "**/ZoneExit20261014Test*", "**/ZamboniExecutionTest*", "**/EdgeContactSimulationTest*", "**/NoGoPivotMarginTest*", "**/OuterOffsetTest*",
    "**/RollingTurnExecutionTest*")
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
    // Two slots on a machine with at least 8 cores: a quick run no longer waits for a full sweep run to finish
    // (11 minutes of waiting on 2026-10-30). Below that, one slot, as before.
    val cpus = Runtime.getRuntime().availableProcessors()
    parameters.slots.set(providers.gradleProperty("testSlots").orNull?.toInt() ?: if (cpus >= 8) 2 else 1)
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // Local val: the doFirst action captures the provider itself, instead of re-reading a script property at
    // execution time (which the configuration cache cannot serialize).
    val slots = testSlots
    val sweeps = name.endsWith("PlannerSweeps")
    // Sweeps, two processes by default: the route preparations of each one already use up to 8 threads (PreparationPool), so the
    // 10 cores are full. Measured on 2026-10-29: 5 processes, with or without capping the pool, made the long
    // sweep classes (reference map, Zamboni) up to three times slower and the full run longer (28 to 31 minutes).
    // The other unit tests, four: the longest classes there wait in real time (gateway sockets,
    // robot bench), so 2 / 3 / 4 / 5 processes took 4:23 / 3:53 / 2:58 / 3:30 (measured on 2026-10-29).
    // Fork counts derived from the cores (10 cores here: 2 for a full run, 4 for a quick one, both measured on
    // 2026-10-29), so that another machine does not have to be recalibrated by hand.
    val cpus = Runtime.getRuntime().availableProcessors()
    maxParallelForks = providers.gradleProperty("testForks").orNull?.toInt()
        ?: if (sweeps) (cpus / 5).coerceIn(1, 4) else (cpus / 2.5).toInt().coerceIn(2, 6)
    // Stop at the first failing class (-PtestFailFast), instead of running the remaining ones.
    if (providers.gradleProperty("testFailFast").isPresent) failFast = true
    // 1 GB per fork instead of 512 MB (fewer G1 cycles on the large sweeps).
    maxHeapSize = "1g"
    usesService(testSlots)
    doFirst { slots.get().acquire(logger) }
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
    if (sweeps) include(plannerSweeps) else exclude(plannerSweeps)
}
// One Test task is a single unit for the up-to-date check and the build cache: when the sweeps were a filter of
// test<Variant>UnitTest, a quick check followed by the full suite ran the 2 200 other tests a second time. In a task of
// their own, the full suite after a quick check only adds the sweeps. Same classes, classpath and JVM arguments as the
// variant's unit tests, run after them so that the two process pools never load the cores at the same time.
afterEvaluate {
    for (variant in listOf("Debug", "Fold", "Release")) {
        val unitTests = tasks.named<org.gradle.api.tasks.testing.Test>("test${variant}UnitTest")
        tasks.register<org.gradle.api.tasks.testing.Test>("test${variant}PlannerSweeps") {
            group = "verification"
            description = "Planner sweeps on the reference map (${variant.lowercase()} variant)."
            val base = unitTests.get()
            testClassesDirs = base.testClassesDirs
            classpath = base.classpath
            jvmArgumentProviders.addAll(base.jvmArgumentProviders)
            javaLauncher.set(base.javaLauncher)
            mustRunAfter(unitTests)
        }
    }
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
// No lint vital on the fold variant: AGP runs it on every assembly of a non-debuggable variant (30 s of analysis
// for a one-line change, 55 s builds instead of 11 s, measured on 2026-10-29), while tools/deploy.sh and
// tools/check.sh full run the complete lintFold, which already reports every fatal issue. Release keeps it.
tasks.matching { it.name in setOf("generateFoldLintVitalReportModel", "lintVitalAnalyzeFold", "lintVitalReportFold", "lintVitalFold") }
    .configureEach { enabled = false }
afterEvaluate {
    checkFoldDexRegisters.configure { dependsOn("packageFold") }
    tasks.named("assembleFold") { finalizedBy(checkFoldDexRegisters) }
    tasks.named("installFold") { dependsOn(checkFoldDexRegisters) }
}

// JVM test coverage, opt-in: ./gradlew :app:testDebugUnitTest :app:lubaCoverageReport -PlubaCoverage
// JaCoCo agent at runtime (rather than AGP instrumentation) so Robolectric tests, whose sandbox reloads
// classes, are counted too. Report: app/build.noindex/reports/jacoco/lubaCoverageReport/ (HTML and XML).
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
