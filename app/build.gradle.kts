plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
}

android {
    namespace = "com.example.ledwallgui"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.ledwallgui"
        minSdk = 33
        targetSdk = 33 // Updated to the latest SDK version
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packagingOptions {
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("com.google.code.gson:gson:2.10.1") // No change needed
    implementation("jp.wasabeef:recyclerview-animators:4.0.1") // No change needed
    implementation("com.google.dagger:hilt-android:2.44")
    implementation("com.google.accompanist:accompanist-permissions:0.28.0")
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("com.android.identity:identity-jvm:202408.1")
    testImplementation("junit:junit:4.13.2") // No change needed
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1") {
        exclude(group = "org.osgi", module = "org.osgi.core") // Exclude the OSGI bundle
    }
    implementation("androidx.compose.material3:material3:1.3.1")
}
