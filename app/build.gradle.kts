plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "green.mobileapps.clippervideocutter"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "green.mobileapps.clippervideocutter"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.3.3"

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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    viewBinding {
        enable = true
    }
    buildFeatures {
        dataBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // ui
    implementation(libs.glide)
    implementation(libs.swiperefreshlayout)
    implementation(libs.androidx.databinding.runtime)
    annotationProcessor(libs.compiler)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.material.v1110)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    // activity ktx for permission request contract
    implementation(libs.androidx.activity.ktx)

    // coroutines for background threading
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
}