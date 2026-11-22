plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.projectcit.emulator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.projectcit.emulator"
        minSdk = 26          // AAudio (Oboe fast path) + adaptive icons
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        // libretro cores are dlopen()'d by path from nativeLibraryDir, so they
        // must be extracted to disk rather than mapped from the APK.
        jniLibs { useLegacyPackaging = true }
    }
}

// ---------------------------------------------------------------------------
// Prebuilt libretro cores. Put buildbot downloads in app/cores/<abi>/, e.g.
//   app/cores/arm64-v8a/mgba_libretro_android.so
// They are renamed to lib*.so (Android only extracts lib*.so from an APK) and
// packaged as native libraries. See app/cores/README.md.
// ---------------------------------------------------------------------------
val stageCores by tasks.registering(Sync::class) {
    from("cores") {
        include("*/*.so")
        rename { name -> if (name.startsWith("lib")) name else "lib$name" }
    }
    into(layout.buildDirectory.dir("generated/cores"))
}
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/cores"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach { dependsOn(stageCores) }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:layout"))
    implementation(project(":core:storage"))
    implementation(project(":core:libretro"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
