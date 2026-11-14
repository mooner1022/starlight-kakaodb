import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins { id("com.android.library") }

android {
    namespace = "party.qwer.hayulgui.stub"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val classesDir = layout.buildDirectory.dir("intermediates/stub-classes")
val assetsDir = layout.buildDirectory.dir("stub-assets")
val extractStub = tasks.register<Copy>("extractStubClasses") {
    dependsOn("bundleReleaseAar")
    from(zipTree(layout.buildDirectory.file("outputs/aar/hayulstub-release.aar"))) { include("classes.jar") }
    into(classesDir)
}
tasks.register<JavaExec>("stubDex") {
    dependsOn(extractStub)
    val sdk = android.sdkDirectory
    classpath = files(File(sdk, "build-tools/35.0.0/lib/d8.jar"))
    mainClass = "com.android.tools.r8.D8"
    val output = assetsDir.get().asFile
    doFirst { output.mkdirs() }
    args(
        "--min-api", "26", "--release", "--lib", File(sdk, "platforms/android-35/android.jar").absolutePath,
        "--output", output.absolutePath, classesDir.get().file("classes.jar").asFile.absolutePath,
    )
    doLast {
        Files.move(File(output, "classes.dex").toPath(), File(output, "stub.dex").toPath(),
            StandardCopyOption.REPLACE_EXISTING)
    }
}
