plugins { kotlin("jvm") version "2.4.10" }

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.4.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.4.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Managers are process-wide singletons (objects): give every test class a fresh JVM.
    forkEvery = 1
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
