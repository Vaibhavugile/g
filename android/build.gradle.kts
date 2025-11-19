// android/build.gradle.kts (project-level / root)

import org.gradle.api.tasks.Delete
import java.io.File

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

// Per-subproject build dir under the new top-level build directory, e.g. ../../build/<module>
subprojects {
    project.buildDir = File(rootProject.buildDir, project.name)
    // Ensure :app is evaluated early if other modules depend on it
    project.evaluationDependsOn(":app")
}

// Register a clean task that deletes the relocated build directory
tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
