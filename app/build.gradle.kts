import java.time.Year
import java.time.LocalDate
import java.security.MessageDigest
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.nio.file.Files

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

val calculatedVersionName = "build-$buildNumber-$currentYear"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.example.forgegen"
    compileSdk = 37

    //noinspection WrongGradleMethod
    room {
        schemaDirectory("$projectDir/schemas")
    }

    defaultConfig {
        applicationId = "com.example.forgegen"
        minSdk = 31
        targetSdk = 37
        // ZASTĄP STATYCZNE WERSJE TYM:
        versionCode = calculatedVersionCode
        versionName = calculatedVersionName

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
    // Jetpack DataStore (Preferences) - Wydajny, asynchroniczny zapis konfiguracji
    implementation(libs.androidx.datastore.preferences)

}

// ==========================================
// 2. ZADANIA (TASKI) AUTOMATYZUJĄCE BUDOWANIE
// ==========================================

// Task inkrementujący build po kompilacji
tasks.register("incrementBuildNumber") {
    doLast {
        val nextBuild = buildNumber + 1
        buildNumberFile.writeText(nextBuild.toString())
        println("=========================================================")
        println("+++ ForgeGen Build Number $buildNumber completed. Next build will be: $nextBuild +++")
        println("=========================================================")
    }
}

// Funkcja rejestrująca taski generujące JSON w zależności od wariantu
fun registerGenerateJsonTask(variant: String, channelName: String, urlSegment: String) {
    val taskName = "generateUpdateJson${variant.replaceFirstChar { it.uppercase() }}"

    tasks.register(taskName) {
        doLast {
            val standardDir = layout.buildDirectory.dir("outputs/apk/$variant").get().asFile
            val intermediateDir = layout.buildDirectory.dir("intermediates/apk/$variant").get().asFile
            val apkFile = standardDir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
                ?: intermediateDir.listFiles()?.firstOrNull { it.name.endsWith(".apk") }

            if (apkFile != null && apkFile.exists()) {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                apkFile.inputStream().use { fis ->
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
                val releaseDate = LocalDate.now().toString()

                val targetApkName = "forgegen-release.apk"
                val serverPort = 7778

                val jsonContent = """
                {
                  "versionCode": $calculatedVersionCode,
                  "versionName": "$calculatedVersionName",
                  "url": "http://192.168.1.142:$serverPort/$targetApkName",
                  "channel": "$channelName",
                  "sha256": "$sha256",
                  "releaseDate": "$releaseDate",
                  "isCritical": false,
                  "changelog": {
                    "en": [
                      "English changelog for build $calculatedVersionCode"
                    ],
                    "pl": [
                      "Polskie tłumaczenie zmian dla buildu $calculatedVersionCode"
                    ]
                  }
                }
                """.trimIndent()

                // Keep local ones in build folder
                val localJsonFile = File(apkFile.parentFile, "update.json")
                localJsonFile.writeText(jsonContent)
                println("Generated local update manifest: ${localJsonFile.absolutePath}")
            } else {
                println("--- WARNING: APK file not found in $standardDir. Skipped update.json ---")
            }
        }
    }
}

// Rejestrujemy task dla obu wariantów
registerGenerateJsonTask("debug", "Release", "release")
registerGenerateJsonTask("release", "Release", "release")

// Task kopiujący pliki na serwer aktualizacji
tasks.register("copyAndPasteUpdateFileIntoServer") {
    group = "publishing"
    description = "Copies built APK and manifest to updateServer"
    
    mustRunAfter("generateUpdateJsonDebug")
    mustRunAfter("generateUpdateJsonRelease")

    doLast {
        val targetApkName = "forgegen-release.apk"
        val targetFolder = "release"

        val releaseDirOutputs = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val releaseDirIntermediates = layout.buildDirectory.dir("intermediates/apk/release").get().asFile
        val debugDirOutputs = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val debugDirIntermediates = layout.buildDirectory.dir("intermediates/apk/debug").get().asFile

        val releaseApk = releaseDirOutputs.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
            ?: releaseDirIntermediates.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
        val debugApk = debugDirOutputs.listFiles()?.firstOrNull { it.name.endsWith(".apk") }
            ?: debugDirIntermediates.listFiles()?.firstOrNull { it.name.endsWith(".apk") }

        val apkFile = when {
            releaseApk != null && releaseApk.exists() && debugApk != null && debugApk.exists() -> {
                if (releaseApk.lastModified() > debugApk.lastModified()) releaseApk else debugApk
            }
            releaseApk != null && releaseApk.exists() -> releaseApk
            debugApk != null && debugApk.exists() -> debugApk
            else -> null
        }

        val localJsonFile = if (apkFile != null) File(apkFile.parentFile, "update.json") else null

        if (apkFile != null && apkFile.exists() && localJsonFile != null && localJsonFile.exists()) {
            val serverDir = rootDir.resolve("updateServer").resolve(targetFolder)
            serverDir.mkdirs()
            
            var uploadSuccess = false
            try {
                println("Attempting to upload to Forge server at http://10.8.0.1:7860/app/upload...")
                val boundary = "Boundary-" + System.currentTimeMillis()
                // You can change the IP here if the server is running on a different machine
                val url = URL("http://10.8.0.1:7860/app/upload")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.connectTimeout = 5000
                connection.readTimeout = 15000
                
                connection.outputStream.use { outputStream ->
                    val writer = PrintWriter(OutputStreamWriter(outputStream, "UTF-8"), true)
                    
                    // add update_json
                    writer.append("--$boundary\r\n")
                    writer.append("Content-Disposition: form-data; name=\"update_json\"; filename=\"${localJsonFile.name}\"\r\n")
                    writer.append("Content-Type: application/json\r\n\r\n")
                    writer.flush()
                    Files.copy(localJsonFile.toPath(), outputStream)
                    outputStream.flush()
                    writer.append("\r\n")
                    
                    // add apk_file
                    writer.append("--$boundary\r\n")
                    writer.append("Content-Disposition: form-data; name=\"apk_file\"; filename=\"${apkFile.name}\"\r\n")
                    writer.append("Content-Type: application/vnd.android.package-archive\r\n\r\n")
                    writer.flush()
                    Files.copy(apkFile.toPath(), outputStream)
                    outputStream.flush()
                    writer.append("\r\n")
                    
                    writer.append("--$boundary--\r\n")
                    writer.close()
                }
                
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    println("=========================================================")
                    println("✅ SUCCESS: Uploaded APK and JSON automatically to Forge server!")
                    println("=========================================================")
                    uploadSuccess = true
                } else {
                    println("⚠️ WARNING: Upload failed with HTTP $responseCode. Falling back to copy task.")
                }
            } catch (e: Exception) {
                println("⚠️ WARNING: Connection to Forge server failed (${e.message}). Server might be offline. Falling back to copy task.")
            }

            if (!uploadSuccess) {
                // Copy APK
                val destApkFile = File(serverDir, targetApkName)
                apkFile.copyTo(destApkFile, overwrite = true)
    
                // Copy JSON
                val destJsonFile = File(serverDir, "update.json")
                localJsonFile.copyTo(destJsonFile, overwrite = true)
    
                println("=========================================================")
                println("✅ SUCCESS: copyAndPasteUpdateFileIntoServer completed via COPY!")
                println("📁 SOURCE APK: file://${apkFile.absolutePath}")
                println("📁 COPIED APK TO: file://${destApkFile.absolutePath}")
                println("📁 COPIED MANIFEST TO: file://${destJsonFile.absolutePath}")
                println("=========================================================")
            }
        } else {
            println("=========================================================")
            println("⚠️ WARNING: Built APK or update.json not found. copyAndPasteUpdateFileIntoServer skipped.")
            println("=========================================================")
        }
    }
}

// Rejestrujemy kolejność wykonywania tasków
tasks.whenTaskAdded {
    if (name == "assembleDebug") {
        finalizedBy("generateUpdateJsonDebug", "copyAndPasteUpdateFileIntoServer", "incrementBuildNumber")
    }
    if (name == "assembleRelease") {
        finalizedBy("generateUpdateJsonRelease", "copyAndPasteUpdateFileIntoServer", "incrementBuildNumber")
    }
}