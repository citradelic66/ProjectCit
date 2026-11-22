plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM module on purpose: every layout decision is a pure function of
// (window size, insets, hinge, options) and is unit-tested without a device.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation(libs.junit)
}
