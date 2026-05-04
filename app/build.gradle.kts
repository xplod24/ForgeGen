import java.time.Year
import java.time.LocalDate
import java.security.MessageDigest
import java.io.File

// ==========================================
// 1. ODCZYT KONFIGURACJI I WERSJONOWANIE
// ==========================================
val versionMajor = project.findProperty("VERSION_MAJOR")?.toString()?.toIntOrNull() ?: 1
val versionPatch = project.findProperty("VERSION_PATCH")?.toString()?.toIntOrNull() ?: 1

val buildNumberFile = file("build_number.txt")
var buildNumber = 1
if (buildNumberFile.exists()) {
    buildNumber = buildNumberFile.readText().trim().toIntOrNull() ?: 1
}

val currentYear = Year.now().value

// ZMIANA: versionCode jest teraz dokładnie równy buildNumber
val calculatedVersionCode = buildNumber

val formattedPatch = String.format("%05d", versionPatch)
val formattedBuild = String.format("%04d", buildNumber)
val calculatedVersionName = "$versionMajor.$currentYear.$formattedPatch-$formattedBuild"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.example.forgegen"
    compileSdk = 36

    //noinspection WrongGradleMethod
    room {
        schemaDirectory("$projectDir/schemas")
    }

    defaultConfig {
        applicationId = "com.example.forgegen"
        minSdk = 31
        targetSdk = 36
        // ZASTĄP STATYCZNE WERSJE TYM:
        versionCode = calculatedVersionCode
        versionName = calculatedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies{
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // optional - Kotlin Extensions and Coroutines support for Room
    implementation(libs.androidx.room.ktx)
    // If this project only uses Java source, use the Java annotationProcessor
    // No additional plugins are necessary
    annotationProcessor(libs.androidx.room.compiler)
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // Jetpack DataStore (Preferences) - Wydajny, asynchroniczny zapis konfiguracji
    implementation("androidx.datastore:datastore-preferences:1.1.1")

}

// ==========================================
// 2. ZADANIA (TASKI) AUTOMATYZUJĄCE BUDOWANIE
// ==========================================

// Task inkrementujący build po kompilacji
tasks.register("incrementBuildNumber") {
    doLast {
        val nextBuild = buildNumber + 1
        buildNumberFile.writeText(nextBuild.toString())
        println("+++ ForgeGen Build Number inkrementowano na: $nextBuild +++")
    }
}

// Funkcja rejestrująca taski generujące JSON w zależności od wariantu
fun registerGenerateJsonTask(variant: String, channelName: String, urlSegment: String) {
    val taskName = "generateUpdateJson${variant.replaceFirstChar { it.uppercase() }}"

    tasks.register(taskName) {
        doLast {
            val standardDir = layout.buildDirectory.dir("outputs/apk/$variant").get().asFile
            val apkFile = standardDir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }

            if (apkFile != null && apkFile.exists()) {
                // USUNIĘTO prefix 'java.security.', ponieważ mamy to w imporcie
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                apkFile.inputStream().use { fis ->
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                // Bezpieczne operowanie na zmiennej we wnętrzu funkcji lambda
                val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }

                // USUNIĘTO prefix 'java.time.'
                val releaseDate = LocalDate.now().toString()

                val jsonContent = """
                {
                  "versionCode": $calculatedVersionCode,
                  "versionName": "$calculatedVersionName",
                  "url": "https://xplod24.ddns.net/$urlSegment/${apkFile.name}",
                  "channel": "$channelName",
                  "sha256": "$sha256",
                  "releaseDate": "$releaseDate",
                  "isCritical": false,
                  "changelog": {
                    "en": [
                      "Replace with english changelog."
                    ],
                    "pl": [
                      "Podmień na listę zmian po polsku."
                    ]
                  }
                }
                """.trimIndent()

                val jsonFile = File(apkFile.parentFile, "update.json")
                jsonFile.writeText(jsonContent)

                // Generowanie widocznego i klikalnego linku dla Android Studio
                println("=========================================================")
                println("✅ SUKCES! Plik APK oraz update.json są gotowe do wysyłki!")
                println("📁 LOKALIZACJA: file://${jsonFile.absolutePath}")
                println("🔑 KANAŁ: $channelName | SHA-256: $sha256")
                println("=========================================================")
            } else {
                println("--- OSTRZEŻENIE: Nie znaleziono pliku APK w $standardDir. Pominięto update.json ---")
            }
        }
    }
}

// Rejestrujemy taski dla obu wariantów
registerGenerateJsonTask("debug", "Beta", "beta")
registerGenerateJsonTask("release", "Stable", "release")

// Rejestrujemy kolejność wykonywania tasków
tasks.whenTaskAdded {
    if (name == "assembleDebug") {
        // Debug generuje Beta JSON
        finalizedBy("generateUpdateJsonDebug", "incrementBuildNumber")
    }
    if (name == "assembleRelease") {
        // Release generuje Stable JSON
        finalizedBy("generateUpdateJsonRelease", "incrementBuildNumber")
    }
}