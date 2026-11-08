import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension

plugins {
    kotlin("multiplatform") version "2.4.20"
}

group = "app.keepfin"
version = "1.0"

// Fixtures are the shared source of truth for behaviour; the runner finds them through this path.
val fixturesRoot = rootProject.layout.projectDirectory.dir("../fixtures/engine").asFile.absolutePath
// Every fixture folder (engine, library-mapping, cloud, net) is a test input, so a changed fixture always re-runs the tests.
val allFixturesRoot = rootProject.layout.projectDirectory.dir("../fixtures").asFile.absolutePath
val commonMainRoot = rootProject.layout.projectDirectory.dir("src/commonMain").asFile.absolutePath

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    js {
        outputModuleName = "keepfin-core"
        nodejs {
            // Mocha's 2 s default is too tight for the sync core's randomized suites on a busy machine.
            testTask { useMocha { timeout = "60s" } }
        }
        binaries.library()
        useEsModules()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The JVM driver of the shared SQL merge state tests (see TestSql.jvm.kt): one known SQLite with its natives.
        jvmTest.dependencies {
            implementation("org.xerial:sqlite-jdbc:3.46.1.0")
        }
    }
}

// Use the Node.js on PATH instead of downloading one.
rootProject.extensions.configure<NodeJsEnvSpec> { download.set(false) }

// Test-runner tooling (security alerts): the overrides lift transitive copies of serialize-javascript and minimatch
rootProject.extensions.configure<NpmExtension> {
    override("serialize-javascript", "^7.1.3")
    override("minimatch", "^11.0.1")
}

tasks.withType<Test>().configureEach {
    systemProperty("keepfin.fixtures", fixturesRoot)
    systemProperty("keepfin.commonMain", commonMainRoot)
    inputs.dir(commonMainRoot).withPropertyName("commonMainSources")
    inputs.dir(allFixturesRoot).withPropertyName("fixtures")
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    environment("KEEPFIN_FIXTURES", fixturesRoot)
    environment("KEEPFIN_COMMON_MAIN", commonMainRoot)
    inputs.dir(allFixturesRoot).withPropertyName("fixtures")
}
