plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// ==========================================
// VERSIONING
// ==========================================
// The version lives in gradle.properties (VERSION_MAJOR / VERSION_MINOR / VERSION_PATCH). Raising it and pushing
// to master is what publishes a release: the release workflow tags it "v<major>.<minor>.<patch>".
// versionCode = major * 1_000_000 + minor * 1_000 + patch, e.g. 1.4.12 -> 1004012. The in-app updater computes
// the same number from the release tag (versionCodeFromTag in ForgeModels.kt), so keep both formulas in sync.
fun versionPart(name: String): Int =
    providers.gradleProperty(name).orNull?.trim()?.toIntOrNull()
        ?: throw GradleException("gradle.properties: $name must be a number")

val versionMajor = versionPart("VERSION_MAJOR")
val versionMinor = versionPart("VERSION_MINOR")
val versionPatch = versionPart("VERSION_PATCH")
if (versionMajor !in 0..2099 || versionMinor !in 0..999 || versionPatch !in 0..999) {
    throw GradleException("Version $versionMajor.$versionMinor.$versionPatch: minor and patch must be below 1000")
}
val appVersionName = "$versionMajor.$versionMinor.$versionPatch"
val appVersionCode = versionMajor * 1_000_000 + versionMinor * 1_000 + versionPatch

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
        // Local debug builds only; GitHub releases are release builds signed with the release key below.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The release key is never committed. The release workflow decodes it from the RELEASE_KEYSTORE_BASE64
        // secret; for a local release build set RELEASE_KEYSTORE_FILE and RELEASE_KEYSTORE_PASSWORD (environment
        // or ~/.gradle/gradle.properties). Without them the release APK is built unsigned and cannot be installed.
        // Every release must be signed with this same key, or phones refuse to update the installed app.
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
        // Releases (from 1.1.0) are this package, signed with the release key. Debug builds add ".debug" and are
        // a separate app; up to 1.0.2 the GitHub releases were such debug builds.
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
