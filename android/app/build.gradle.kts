plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.dudnyk.corolla"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dudnyk.corolla"
        minSdk = 31
        targetSdk = 33
        versionCode = 6
        versionName = "0.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        compose = true
    }
    testOptions {
        unitTests.all {
            // SimIntegrationTest reads these; without SIM_TCP it is skipped
            it.environment("SIM_TCP", System.getenv("SIM_TCP") ?: "")
            it.environment("SIM_OUT", System.getenv("SIM_OUT") ?: "")
            it.environment("SIM_SECONDS", System.getenv("SIM_SECONDS") ?: "")
            it.environment("SIM_FUEL_PERIOD", System.getenv("SIM_FUEL_PERIOD") ?: "")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
