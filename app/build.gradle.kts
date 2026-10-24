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
        versionCode = 183
        versionName = "1.15.0"
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
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxParallelForks = 2
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
    if (providers.gradleProperty("lubaQuickTests").isPresent) {
        exclude("**/ReferenceMapPlannerTest*", "**/ReferenceTransitTest*", "**/ReferenceSimulationTest*")
    }
}

// ART safeguard: a method with more than ~256 Dalvik registers (large Compose function) was
// miscompiled by D8 in the non-debuggable variant, causing a VerifyError when opening the map.
val checkFoldDexRegisters by tasks.registering(Exec::class) {
    group = "verification"
    description = "Refuse l'APK fold si une méthode de l'app dépasse 224 registres Dalvik."
    val apk = layout.buildDirectory.file("outputs/apk/fold/app-fold.apk")
    inputs.file(apk)
    commandLine("python3", rootProject.file("tools/check-dex-registers.py").absolutePath, apk.get().asFile.absolutePath, "224")
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
