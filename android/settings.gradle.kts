pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "Keepfin"
// The shared Kotlin core (decision logic used by both apps) builds as part of this build.
includeBuild("../core")
include(":app")
