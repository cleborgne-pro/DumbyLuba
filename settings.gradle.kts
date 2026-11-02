pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "DumbyLuba"
include(":app")
// Kotlin compiler state in .kotlin.noindex/ instead of .kotlin/, out of the Spotlight index like build.noindex/ (root
// build.gradle.kts). Absolute path set here: a relative kotlin.project.persistent.dir in gradle.properties is resolved
// against the Kotlin daemon's working directory and failed the compilation (NoSuchFileException, KGP 2.1).
gradle.beforeProject { extensions.extraProperties["kotlin.project.persistent.dir"] = rootDir.resolve(".kotlin.noindex").path }
