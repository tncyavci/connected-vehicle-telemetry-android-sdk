plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.tink)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}
