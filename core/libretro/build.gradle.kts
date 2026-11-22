plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.projectcit.libretro"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                // -O2 even in debug: the audio callback and per-frame copies are real-time paths.
                cppFlags += listOf("-std=c++17", "-fno-rtti", "-O2")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    buildFeatures { prefab = true }  // Oboe ships its headers/libs as a prefab AAR
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.oboe)
    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Host (Linux) build of the native code, so the libretro host is tested against
// real cores on the build machine: no device or emulator needed.
//   ./gradlew :core:libretro:nativeHostTest            C++ tests under ASan/UBSan
//   ./gradlew :core:libretro:testDebugUnitTest         Kotlin -> JNI -> host tests
// Pass -PmgbaCore=/path/to/mgba_libretro.so (Linux build) to include real-mGBA tests.
// ---------------------------------------------------------------------------
val isLinuxHost = System.getProperty("os.name").startsWith("Linux")
val sdkCmakeBin = android.sdkDirectory.resolve("cmake/${libs.versions.cmake.get()}/bin")
val mgbaCore = (findProperty("mgbaCore") as String?) ?: System.getenv("MGBA_LIBRETRO") ?: ""
val javaHomeForJni = System.getProperty("java.home")

fun registerHostBuild(name: String, dirName: String, cxxFlags: String) = tasks.register<Exec>(name) {
    group = "verification"
    description = "Builds the libretro host + test core for the Linux build machine ($dirName)"
    onlyIf { isLinuxHost }
    val out = layout.buildDirectory.dir(dirName).get().asFile
    inputs.dir("src/main/cpp"); inputs.dir("src/test/native")
    outputs.dir(out)
    environment("JAVA_HOME", javaHomeForJni)
    commandLine("bash", "-c", """
        set -e
        "${sdkCmakeBin}/cmake" -S src/test/native -B "${out}" -G Ninja \
          -DCMAKE_MAKE_PROGRAM="${sdkCmakeBin}/ninja" -DCMAKE_BUILD_TYPE=Debug -DCMAKE_CXX_FLAGS="$cxxFlags" >/dev/null
        "${sdkCmakeBin}/ninja" -C "${out}" >/dev/null
    """.trimIndent())
}

val buildHostNative = registerHostBuild("buildHostNative", "host-native", "")
val buildHostNativeSanitized = registerHostBuild("buildHostNativeSanitized", "host-native-asan", "-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all")

val nativeHostTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the C++ libretro host tests under AddressSanitizer/UBSan"
    onlyIf { isLinuxHost }
    dependsOn(buildHostNativeSanitized)
    val dir = layout.buildDirectory.dir("host-native-asan").get().asFile
    val scratch = layout.buildDirectory.dir("tmp/host-test").get().asFile
    doFirst { scratch.deleteRecursively(); scratch.mkdirs() }
    commandLine(listOf("$dir/host_tests", "$dir/testcore.so", scratch.path) + listOfNotNull(mgbaCore.takeIf { it.isNotBlank() }))
}

tasks.withType<Test>().configureEach {
    if (isLinuxHost) {
        dependsOn(buildHostNative)
        val dir = layout.buildDirectory.dir("host-native").get().asFile
        systemProperty("java.library.path", "$dir/retrohost")
        systemProperty("retrohost.testcore", "$dir/testcore.so")
        systemProperty("retrohost.mgba", mgbaCore)
    }
}
tasks.named("check") { dependsOn(nativeHostTest) }
