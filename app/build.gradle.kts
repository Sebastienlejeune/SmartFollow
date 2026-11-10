plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zamateka.smartfollow"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zamateka.smartfollow"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "0.8.2"
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

    buildFeatures {
        compose = true
    }

    // Lists the app languages (values, values-fr) for the per-app language of Android 13+.
    androidResources {
        generateLocaleConfig = true
    }

    // Private sound themes (files that cannot be published), ignored by git; see README.
    sourceSets.getByName("main").res.srcDir("src/private/res")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":alarm-engine"))
    implementation(project(":data-share"))
    implementation(project(":monitor"))
    implementation(project(":sync"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // In-memory Dexcom Share server of the "demo" accounts (debug builds only).
    debugImplementation(libs.ktor.client.mock)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.okhttp)

    testImplementation(libs.kotlin.test.junit)
}
