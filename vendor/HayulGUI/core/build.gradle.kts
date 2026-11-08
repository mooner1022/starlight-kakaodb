plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "party.qwer.hayulgui.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir("../stub/build/stub-assets")
}

dependencies {
    implementation(libs.apksig)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// stub.dex 산출물이 merge 전 준비되어 있도록 wire.
tasks.configureEach {
    if ((name.startsWith("merge") || name.startsWith("package")) &&
        name.contains("Assets", ignoreCase = true) &&
        !name.contains("JniLib", ignoreCase = true)) {
        dependsOn(":stub:stubDex")
    }
}
