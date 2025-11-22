import org.gradle.api.tasks.Delete
import java.io.File
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.compile.JavaCompile
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.AppExtension

// buildscript: provide classpath dependencies required by the app module (e.g. google-services)
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.google.gms:google-services:4.3.15")
    }
}

// allprojects repositories so modules can resolve their dependencies
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Relocate the top-level build directory up two levels: ../../build
rootProject.buildDir = File(rootProject.projectDir, "../../build")

// GLOBAL JVM-17 enforcement for all modules
subprojects {
    // --- Pre-configure JavaCompile ---
    tasks.withType(JavaCompile::class.java).configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }

    // --- Pre-configure KotlinCompile ---
    tasks.withType(KotlinCompile::class.java).configureEach {
        kotlinOptions {
            jvmTarget = "17"
        }
    }

    // Early attempt for Android compileOptions
    plugins.withId("com.android.library") {
        try {
            val libExt = extensions.findByType(LibraryExtension::class.java)
            libExt?.compileOptions?.apply {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        } catch (_: Exception) { }
    }
    plugins.withId("com.android.application") {
        try {
            val appExt = extensions.findByType(AppExtension::class.java)
            appExt?.compileOptions?.apply {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        } catch (_: Exception) { }
    }

    // --- After evaluate override (catches plugins that change settings later) ---
    afterEvaluate {
        try {
            // Java
            tasks.withType(JavaCompile::class.java).configureEach {
                sourceCompatibility = JavaVersion.VERSION_17.toString()
                targetCompatibility = JavaVersion.VERSION_17.toString()
            }

            // Kotlin
            tasks.withType(KotlinCompile::class.java).configureEach {
                kotlinOptions {
                    jvmTarget = "17"
                }
            }

            // Android compileOptions (library)
            if (plugins.hasPlugin("com.android.library")) {
                val libExt = extensions.findByType(LibraryExtension::class.java)
                libExt?.compileOptions?.apply {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            // Android compileOptions (app)
            if (plugins.hasPlugin("com.android.application")) {
                val appExt = extensions.findByType(AppExtension::class.java)
                appExt?.compileOptions?.apply {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

        } catch (_: Exception) { }
    }

    // Maintain relocated dirs
    project.buildDir = File(rootProject.buildDir, project.name)
    evaluationDependsOn(":app")
}

// ------------------------------
// Per-module override: force JVM 17 for shared_preferences_android
// ------------------------------
project(":shared_preferences_android") {
    afterEvaluate {
        try {
            // Force JavaCompile tasks to target Java 17
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).configureEach {
                sourceCompatibility = JavaVersion.VERSION_17.toString()
                targetCompatibility = JavaVersion.VERSION_17.toString()
            }

            // Force Kotlin jvmTarget = 17
            tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach {
                kotlinOptions {
                    jvmTarget = "17"
                }
            }

            // Force compileOptions for Android library module
            plugins.withId("com.android.library") {
                try {
                    val libExt = extensions.findByType(com.android.build.gradle.LibraryExtension::class.java)
                    libExt?.compileOptions?.apply {
                        sourceCompatibility = JavaVersion.VERSION_17
                        targetCompatibility = JavaVersion.VERSION_17
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to set compileOptions for shared_preferences_android: ${e.message}")
                }
            }

            // Double-force Java values
            tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).forEach {
                it.sourceCompatibility = JavaVersion.VERSION_17.toString()
                it.targetCompatibility = JavaVersion.VERSION_17.toString()
            }
        } catch (ex: Exception) {
            logger.warn("Per-module override for :shared_preferences_android failed: ${ex.message}")
        }
    }
}

// Clean task
tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
