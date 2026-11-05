plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
android {
    sourceSets {
        // One fixture runner per area (music, providers) for the JVM unit test and the device test (same code, two places that read the fixtures).
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
        // The engine fixtures travel only inside the test APK; they are never part of the app.
        getByName("androidTest").assets.srcDir("../../fixtures")
    }
    namespace = "app.keepfin"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.keepfin"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "1.0.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        // Cloud accounts: the public client ids of this build (build variables, never secrets; empty means the provider shows "Not available in this build").
        // Passed as -PKEEPFIN_GOOGLE_CLIENT_ID=... or as an environment variable of the same name. Google's Android client is registered by package name and signing certificate.
        for (name in listOf("KEEPFIN_GOOGLE_CLIENT_ID", "KEEPFIN_MS_CLIENT_ID", "KEEPFIN_DROPBOX_APP_KEY")) {
            val value = ((project.findProperty(name) as String?) ?: System.getenv(name) ?: "").trim()
            buildConfigField("String", name, "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        }
        // The custom-scheme redirect of the browser sign-in (Microsoft, Dropbox): app.keepfin:/oauth
        manifestPlaceholders["appAuthRedirectScheme"] = "app.keepfin"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { jniLibs.useLegacyPackaging = true }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    // Shared decision logic (music matching), included build ../core; Gradle picks its jvm variant.
    implementation("app.keepfin:keepfin-core:1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Espresso 3.7 runs on Android 14+ (older ones call a removed InputManager method, which broke every Compose UI test on API 36)
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.documentfile:documentfile:1.0.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    implementation("androidx.paging:paging-common:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")
    androidTestImplementation("androidx.metrics:metrics-performance:1.0.0")
    implementation("androidx.work:work-runtime:2.12.0")
    // Cloud accounts: browser sign-in with PKCE and a custom-scheme redirect (AppAuth, Apache-2.0); Google's own authorization (Play services, drive.file).
    implementation("net.openid:appauth:0.11.1")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    // Drag-to-reorder for the Spaces hub grid (Apache-2.0).
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    androidTestImplementation("androidx.work:work-testing:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    val composeBom = platform("androidx.compose:compose-bom:2026.03.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    // The network client of Devices (pinned TLS 1.3 to one paired device, WebSocket events); already on the classpath through Coil.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Pairing codes: zxing core draws the QR (Apache-2.0); zxing-android-embedded scans it with the camera (Apache-2.0). Its own copy of
    // the core is left out so the newer one below is the only one.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt.coil3:coil-video:3.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
}

// Release notes travel with the installed APK, including when the device is offline.
// The version and source are task inputs: a version bump cannot keep yesterday's notes.
val releaseNotesVersion = requireNotNull(android.defaultConfig.versionName)
val releaseNotesSource = rootProject.file("../docs/release-notes/$releaseNotesVersion.md")
val releaseNotesAssets = layout.buildDirectory.dir("generated/releaseNotes/assets")
val generateReleaseNotes by tasks.registering {
    inputs.property("versionName", releaseNotesVersion)
    inputs.file(releaseNotesSource)
    outputs.dir(releaseNotesAssets)
    doLast {
        require(releaseNotesSource.isFile) {
            "Add docs/release-notes/$releaseNotesVersion.md before building this version."
        }
        val target = releaseNotesAssets.get().file("release-notes.md").asFile
        target.parentFile.mkdirs()
        target.writeText(releaseNotesSource.readText(Charsets.UTF_8), Charsets.UTF_8)
    }
}
android.sourceSets.getByName("main").assets.srcDir(releaseNotesAssets)
tasks.named("preBuild").configure { dependsOn(generateReleaseNotes) }

// The features & how-to guide has one source, content/guide.json. Like the release notes it ships inside the APK; a missing file fails the build.
val guideSource = rootProject.file("../content/guide.json")
val guideAssets = layout.buildDirectory.dir("generated/guide/assets")
val generateGuide by tasks.registering {
    inputs.file(guideSource)
    outputs.dir(guideAssets)
    doLast {
        require(guideSource.isFile) { "Add content/guide.json before building: it is the in-app Features & how-to guide." }
        val target = guideAssets.get().file("guide.json").asFile
        target.parentFile.mkdirs()
        target.writeText(guideSource.readText(Charsets.UTF_8), Charsets.UTF_8)
    }
}
android.sourceSets.getByName("main").assets.srcDir(guideAssets)
tasks.named("preBuild").configure { dependsOn(generateGuide) }
