import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun configProp(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(key)?.takeIf { it.isNotBlank() }

fun buildConfigString(key: String): String {
    val raw = configProp(key) ?: ""
    val escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

val signingStoreFile = configProp("SIGNING_STORE_FILE")?.let { raw ->
    File(raw).let { if (it.isAbsolute) it else rootProject.file(raw) }
}
val signingStorePassword = configProp("SIGNING_STORE_PASSWORD")
val signingKeyAlias = configProp("SIGNING_KEY_ALIAS")
val signingKeyPassword = configProp("SIGNING_KEY_PASSWORD")
val hasReleaseSigning = signingStoreFile != null &&
    signingStoreFile.exists() &&
    signingStorePassword != null &&
    signingKeyAlias != null &&
    signingKeyPassword != null

android {
    namespace = "com.myrunsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myrunsync"
        minSdk = 28
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
            ?: System.getenv("VERSION_CODE")?.toIntOrNull()
            ?: 10000
        versionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
            ?: "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SUPABASE_URL", buildConfigString("SUPABASE_URL"))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString("SUPABASE_ANON_KEY"))
        }
        release {
            // Release APKs must never bundle Supabase credentials.
            buildConfigField("String", "SUPABASE_URL", "\"\"")
            buildConfigField("String", "SUPABASE_ANON_KEY", "\"\"")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Health Connect SDK
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Supabase Kotlin SDK + Ktor OkHttp engine
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.ktor:ktor-client-okhttp:3.1.2")

    // Kotlinx Serialization & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    testImplementation("junit:junit:4.13.2")
}
