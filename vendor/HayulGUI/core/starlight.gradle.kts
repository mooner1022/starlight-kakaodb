import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "party.qwer.hayulgui.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").assets.srcDir(project(":hayulstub").layout.buildDirectory.dir("stub-assets"))
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("com.android.tools.build:apksig:8.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.16.0")
    // Upstream's JVM tests (core/src/test); run with :hayulcore:testReleaseUnitTest.
    testImplementation("junit:junit:4.13.2")
}

tasks.configureEach {
    if ((name.startsWith("merge") || name.startsWith("package")) && name.contains("Assets"))
        dependsOn(":hayulstub:stubDex")
}
