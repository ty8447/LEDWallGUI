import org.gradle.internal.impldep.org.junit.experimental.categories.Categories.CategoryFilter.exclude

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
}

android {
    namespace = "com.example.ledwallgui"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.ledwallgui"
        minSdk = 34
        targetSdk = 34 // Updated to the latest SDK version
        versionCode = 1
        versionName = "1.0"
        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.4")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1") // No change needed
    implementation("jp.wasabeef:recyclerview-animators:4.0.1") // No change needed
    implementation("com.google.dagger:hilt-android:2.48.1")
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("com.android.identity:identity-jvm:202411.1")
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    testImplementation("junit:junit:4.13.2") // No change needed
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    debugImplementation ("com.squareup.leakcanary:leakcanary-android:2.14")
    implementation ("no.nordicsemi.android:ble-ktx:2.9.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1") {
        exclude(group = "org.osgi", module = "org.osgi.core") // Exclude the OSGI bundle
    }
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
}
