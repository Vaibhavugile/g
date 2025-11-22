plugins {
    id("com.android.application")
    id("com.google.gms.google-services") // FlutterFire / Google services
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

android {
    namespace = "com.example.call_leads_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "27.0.12077973"

    compileOptions {
        // Use Java 17 to match your installed JDK (java -version shows 17)
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Keep kotlinOptions here for Android plugin compatibility
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.example.call_leads_app"
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // --- Build types: explicitly disable minify & shrink for both debug & release
    buildTypes {
        debug {
            // Development: always off
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            // Keep them off for now to avoid resource shrinking requirements during dev
            isMinifyEnabled = false
            isShrinkResources = false

            // If you later enable minification, add proguard files here:
            // proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        // Extra safeguard
        all {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // packaging options to reduce conflicts
    packagingOptions {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt"
            )
        }
    }
}

dependencies {
    // Firebase native libs
    implementation("com.google.firebase:firebase-auth-ktx:22.1.1")
    implementation("com.google.firebase:firebase-firestore-ktx:24.6.1")
    implementation("com.google.firebase:firebase-storage-ktx:20.2.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Kotlin standard library (jdk8 is fine for JVM17)
    implementation(kotlin("stdlib-jdk8"))

    // core-ktx
    implementation("androidx.core:core-ktx:1.12.0")
}

flutter {
    source = "../.."
}

// Ensure Kotlin compile tasks in this module use JVM 17 as well.
// This complements the project-level enforcement and helps with some Gradle/Kotlin plugin versions.
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}
