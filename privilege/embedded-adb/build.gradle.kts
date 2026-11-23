import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.lmalakar.onecr.privilege.embeddedadb"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }

    testOptions {
        // Pure-JVM protocol tests: android.* stubs return defaults instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":privilege:api"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))

    // The pairing flow needs SSL_export_keying_material ("adb-label"), which the
    // platform TLS API does not expose — the bundled Conscrypt does.
    implementation(libs.conscrypt.android)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    // JVM-side Conscrypt (native TLS + exporter) for the end-to-end pairing test.
    testImplementation(libs.conscrypt.openjdk.uber)
    testImplementation(project(":core:common"))
}
