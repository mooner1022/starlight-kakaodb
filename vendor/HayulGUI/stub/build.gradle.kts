plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "party.qwer.hayulgui.stub"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // stub 은 의도적으로 dependency 없이 android.jar compileOnly 로만.
    implementation(libs.kotlin.stdlib)
}

// ---------------------------------------------------------------------------
// stubDex: classes.jar(릴리즈 aar) → d8 → stub/build/stub-assets/stub.dex
// core 모듈이 이 디렉터리를 assets srcDir 로 마운트한다.
// ---------------------------------------------------------------------------

val stubAar = layout.buildDirectory.file("outputs/aar/stub-release.aar")
val stubClassesDir = layout.buildDirectory.dir("intermediates/stub-classes")
val stubAssetsDir = layout.buildDirectory.dir("stub-assets")

val extractStubClasses = tasks.register<Copy>("extractStubClasses") {
    dependsOn("bundleReleaseAar")
    from(zipTree(stubAar)) {
        include("classes.jar")
    }
    into(stubClassesDir)
}

val stubDex = tasks.register<JavaExec>("stubDex") {
    group = "hayulgui"
    description = "Compile stub classes to stub.dex via d8"
    dependsOn(extractStubClasses)

    val sdkDir = providers.provider { android.sdkDirectory }.get()
    val androidJar = File(sdkDir, "platforms/android-35/android.jar")
    val d8Classpath = File(sdkDir, "build-tools/35.0.0/lib/d8.jar")

    classpath = files(d8Classpath)
    mainClass.set("com.android.tools.r8.D8")

    val jar = stubClassesDir.get().file("classes.jar").asFile
    val outDir = stubAssetsDir.get().asFile
    doFirst { outDir.mkdirs() }
    args = listOf(
        "--min-api", "26",
        "--release",
        "--lib", androidJar.absolutePath,
        "--output", outDir.absolutePath,
        jar.absolutePath,
    )
    doLast {
        val produced = File(outDir, "classes.dex")
        val target = File(outDir, "stub.dex")
        if (produced.exists()) produced.renameTo(target)
        if (!target.exists() && !produced.exists()) {
            throw GradleException("stubDex: d8 produced no dex in $outDir")
        }
    }
}

// core 모듈이 ../stub/build/stub-assets 를 assets srcDir 로 마운트하며,
// core 의 merge*Assets 태스크가 :stub:stubDex 에 의존하도록 core/build.gradle.kts 에서 wiring 한다.
