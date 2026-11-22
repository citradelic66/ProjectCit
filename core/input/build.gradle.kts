plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM: virtual-pad geometry, multi-touch tracking and controller mapping are
// plain functions of numbers, so every rule is unit-tested without a device.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:layout"))
    testImplementation(libs.junit)
}
