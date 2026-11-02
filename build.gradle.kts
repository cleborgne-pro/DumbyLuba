plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.ktlint) apply false
}
// Build outputs in build.noindex/: Spotlight never indexes a folder whose name ends in .noindex, so a build no longer
// keeps mds busy indexing thousands of class, dex and test result files (40 to 55 % of a core during the test runs of
// 2026-10-31), without a manual exclusion on every machine or for every session worktree.
allprojects { layout.buildDirectory.set(layout.projectDirectory.dir("build.noindex")) }
