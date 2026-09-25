plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// ==========================================
// VERSIONING
// ==========================================
// The version lives in gradle.properties (VERSION_MAJOR / VERSION_MINOR / VERSION_PATCH, and VERSION_MICRO for a
// micro-patch of a release, 0 otherwise). Raising it and pushing to master is what publishes a release: the release
// workflow tags it "v<major>.<minor>.<patch>", or "v<major>.<minor>.<patch>-<micro>" for a micro-patch.
// versionCode = major * 100_000_000 + minor * 100_000 + patch * 100 + micro, e.g. 1.4.12 -> 100401200 and
// 1.1.4-1 -> 100100401. Up to 1.1.4 it was major * 1_000_000 + minor * 1_000 + patch; every new code is higher.
// The in-app updater computes the same number from the release tag (versionCodeFromTag in ForgeModels.kt), so keep
// both formulas in sync.
fun versionPart(name: String): Int =
    providers.gradleProperty(name).orNull?.trim()?.toIntOrNull()
        ?: throw GradleException("gradle.properties: $name must be a number")

val versionMajor = versionPart("VERSION_MAJOR")
val versionMinor = versionPart("VERSION_MINOR")
val versionPatch = versionPart("VERSION_PATCH")
val versionMicro = versionPart("VERSION_MICRO")
if (versionMajor !in 0..20 || versionMinor !in 0..999 || versionPatch !in 0..999 || versionMicro !in 0..99) {
    throw GradleException(
        "Version $versionMajor.$versionMinor.$versionPatch-$versionMicro: major up to 20, minor and patch below 1000, micro below 100",
    )
}
val appVersionName = "$versionMajor.$versionMinor.$versionPatch" + if (versionMicro > 0) "-$versionMicro" else ""
val appVersionCode = versionMajor * 100_000_000 + versionMinor * 100_000 + versionPatch * 100 + versionMicro

tasks.register("printVersionName") {
    val name = appVersionName
    doLast { println(name) }
}

android {
    namespace = "com.example.forgegen"
    compileSdk = 37

    //noinspection WrongGradleMethod
    room {
        schemaDirectory("$projectDir/schemas")
    }

    signingConfigs {
        // The keystore is committed on purpose: the GitHub releases are debug builds, and Android Studio and the
        // release workflow must sign with the same key, otherwise the phone refuses to install a release over the
        // installed app.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Prepared for signed release builds (not published yet). The release key is never committed; set
        // RELEASE_KEYSTORE_FILE and RELEASE_KEYSTORE_PASSWORD (environment or ~/.gradle/gradle.properties).
        // Without them the release APK is built unsigned and cannot be installed.
        val releaseKeystore =
            providers
                .environmentVariable("RELEASE_KEYSTORE_FILE")
                .orElse(providers.gradleProperty("RELEASE_KEYSTORE_FILE"))
                .orNull
        val releasePassword =
            providers
                .environmentVariable("RELEASE_KEYSTORE_PASSWORD")
                .orElse(providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD"))
                .orNull
        if (!releaseKeystore.isNullOrBlank() && !releasePassword.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releasePassword
                keyAlias = "forgegen"
                keyPassword = releasePassword
            }
        }
    }

    defaultConfig {
        // Changed from com.example.forgegen: the GitHub builds are signed with the repository key, so they are
        // a separate app and install next to builds signed with an Android Studio key instead of clashing.
        // Release builds (not published yet) drop the ".debug" suffix and would be another separate app.
        applicationId = "io.github.xplod24.forgegen"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "ForgeGen (Beta)")
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "ForgeGen")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "app_name", "ForgeGen")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
    testOptions {
        // Plain JVM unit tests: android.* calls (e.g. Log) return defaults instead of throwing "not mocked".
        unitTests.isReturnDefaultValues = true
    }
}

// CHANGELOG.md goes into the app's assets for the "What's New" dialog shown after an update (WhatsNew.kt).
abstract class CopyChangelogTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelog: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        changelog.get().asFile.copyTo(outputDir.file("CHANGELOG.md").get().asFile, overwrite = true)
    }
}

val copyChangelog =
    tasks.register<CopyChangelogTask>("copyChangelog") {
        changelog.set(layout.projectDirectory.file("../CHANGELOG.md"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyChangelog, CopyChangelogTask::outputDir)
    }
}

dependencies{
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // optional - Kotlin Extensions and Coroutines support for Room
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.text)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Navigation for Compose
    implementation(libs.androidx.navigation.compose)

    // Networking & JSON
    implementation(libs.okhttp)
    implementation(libs.gson)

    // Image Loading (Coil handles Base64 and URLs easily in Compose)
    implementation(libs.coil.compose)

    // ViewModel integration
    implementation(libs.lifecycle.viewmodel.compose)


}
