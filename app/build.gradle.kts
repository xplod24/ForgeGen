plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// ==========================================
// VERSIONING
// ==========================================
// versionCode = 1000 + number of commits. It grows with every commit and is identical for a local build and
// the CI build of the same commit, so the in-app updater always sees a newer GitHub release as an update.
// (The offset keeps it above the codes of the old build_number.txt scheme, which reached 277.)
// Needs the full git history: the release workflow checks out with fetch-depth 0.
val gitCommitCount: Int? =
    runCatching {
        providers
            .exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                isIgnoreExitValue = true // outside a git checkout fall back below instead of failing the build
            }.standardOutput.asText
            .get()
            .trim()
            .toIntOrNull()
    }.getOrNull()

if (gitCommitCount == null) logger.warn("ForgeGen: git commit count unavailable, using versionCode 1000")

val appVersionCode: Int = 1000 + (gitCommitCount ?: 0)

tasks.register("printVersionCode") {
    val code = appVersionCode
    doLast { println(code) }
}

android {
    namespace = "com.example.forgegen"
    compileSdk = 37

    //noinspection WrongGradleMethod
    room {
        schemaDirectory("$projectDir/schemas")
    }

    signingConfigs {
        // The keystore is committed on purpose: Android Studio and the release workflow must sign with the
        // same key, otherwise the phone refuses to install a GitHub release over the installed app.
        // To keep an installation built with your own key, replace this file with ~/.android/debug.keystore.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.example.forgegen"
        minSdk = 31
        targetSdk = 37
        versionCode = appVersionCode
        versionName = "build-$appVersionCode"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "ForgeGen (Beta)")
    }

    buildTypes {
        release {
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
            resValue("string", "app_name", "ForgeGen (Beta)")
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
