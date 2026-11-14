import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.mooner1022.slplugin")
    // With KSP, slplugin turns the KDoc of the classes scripts reach into starlight/agent/kakaodb.api.json, which
    // StarLight AI (StarLight 1.1.0) reads along with the guides in src/main/assets/agent/guides.
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.mooner.kakaodb"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mooner.kakaodb"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        // Used as the plugin version in starlight.json.
        versionName = "0.1.0"
        // What the installed shared UID helper should report (see sharedHelperBuild in the root build.gradle.kts).
        buildConfigField("String", "SHARED_HELPER_BUILD", "\"${rootProject.extra["sharedHelperBuild"]}\"")
    }
    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("main") {
            // The wire format shared with the root helper (:rootd).
            java.srcDir("../rootd/src/protocol/java")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

/**
 * StarLight re-extracts a plugin's assets only when assetRevision changes, and the default revision doesn't see the
 * generated rootd.apk. So it is derived from what the assets are made of: [sources] (files or directories), by path
 * and content; rootd/build.gradle.kts holds the helper's dependency versions. A change that shows in none of them (the
 * Android Gradle plugin's version...) needs SALT bumped to reach devices.
 */
fun assetRevisionOf(sources: FileCollection): Int {
    val salt = "rootd.apk and embedded setup assets 2"
    val crc = CRC32()
    crc.update(salt.toByteArray(Charsets.UTF_8))
    for (file in sources.asFileTree.files.sortedBy { rootProject.relativePath(it) }) {
        crc.update(rootProject.relativePath(file).replace('\\', '/').toByteArray(Charsets.UTF_8))
        crc.update(file.readBytes())
    }
    // A positive Int, never 0 (the default) or -1 (what StarLight reads when nothing was extracted yet).
    return (crc.value % 0x7ffffffeL).toInt() + 1
}

starlight {
    sdk {
        // Build these from the StarLight repository: ./gradlew :PluginCore:assembleRelease :ConfigDSL:assembleRelease.
        // PluginCore has full and play flavors since 1.1.0; this is PluginCore-full-release.aar, as plugins load only
        // in the full app.
        pluginCore = file("libs/PluginCore-release.aar")
        configDsl = file("libs/ConfigDSL-release.aar")
        excludeHostKotlinLibraries = true
    }
    // Generates assets/starlight.json.
    manifest {
        // StarLight extracts the assets again only when this changes; see assetRevisionOf above.
        assetRevision = assetRevisionOf(files(
            "src/main/assets", "../rootd/src", "../rootd/build.gradle.kts",
            "../sharedd/src", "../sharedd/build.gradle.kts", "../setup/src", "../setup/build.gradle.kts",
            "../vendor/HayulGUI/core/src/main", "../vendor/HayulGUI/core/starlight.gradle.kts",
            "../vendor/HayulGUI/stub/src/main", "../vendor/HayulGUI/stub/starlight.gradle.kts",
        ))
        id = "kakaodb"
        name = "KakaoTalk DB"
        mainClass = "dev.mooner.kakaodb.KakaoDBPlugin"
        // ConfigDSL info/choice/textList and button actions, and the initial setup (isSetupRequired, getSetupStructure,
        // onSetup, requestSetup), need PluginCore 1.0.0 (StarLight nightly). A StarLight without the setup never asks
        // for it: the plugin settings work as before. StarLight AI's docs use no PluginCore API (StarLight 1.1.0 reads
        // them from the package), and slplugin finds no 1.1.0 API in the code, so older hosts still load the plugin.
        apiVersion = "1.0.0"
        authors.add("mooner")
        description = "Receives KakaoTalk messages, feeds and deletions by reading the KakaoTalk database"
        category = "messenger"
    }
    deploy {
        // Set for the development device: every deploy goes there and force-stops StarLight to restart it. Elsewhere,
        // drop these and pass -Pstarlight.device=<serial or ip:port> (or set STARLIGHT_DEVICE), and opt into the
        // restart per run with -Pstarlight.restartApp=true.
        device = "localhost:5555"
        restartApp = true
    }
}

// ------------------------------------------------------------------ root helper
// RootAccess runs :rootd's APK with su under app_process. It can't run from the plugin's own dex (StarLight's classes,
// Kotlin included, don't exist there), so :rootd builds an APK of its own, shipped as the plugin asset rootd.apk, which
// StarLight extracts next to the plugin's data (StarlightPlugin.getAsset).

/** Copies :rootd's release APK to [outputDir]/rootd.apk, i.e. the asset rootd.apk. */
abstract class RootdApkTask : DefaultTask() {

    @get:InputFiles
    abstract val apkDir: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val apks = apkDir.asFileTree.files.filter { it.name.endsWith(".apk") }
        if (apks.size != 1)
            throw GradleException("Expected one root helper APK in ${apkDir.files}, found $apks")
        val out = outputDir.get().asFile
        // Nothing else may stay there: it all ends up in the plugin's assets (an older build left rootd.jar here).
        out.deleteRecursively()
        out.mkdirs()
        Files.copy(apks.first().toPath(), File(out, "rootd.apk").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val rootdApk = tasks.register<RootdApkTask>("rootdApk") {
    apkDir.from(project(":rootd").layout.buildDirectory.dir("outputs/apk/release"))
    apkDir.builtBy(":rootd:assembleRelease")
    outputDir.set(layout.buildDirectory.dir("generated/rootd"))
}

val sharedHelperApk = tasks.register<Copy>("sharedHelperApk") {
    dependsOn(":sharedd:assembleRelease")
    from(project(":sharedd").layout.buildDirectory.file("outputs/apk/release/sharedd-release.apk"))
    into(layout.buildDirectory.dir("generated/shared-helper"))
    rename { "sharedd.apk" }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(rootdApk, RootdApkTask::outputDir)
    }
}

android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/shared-helper"))
tasks.configureEach {
    if ((name.startsWith("merge") && name.contains("Assets")) || name.lowercase().contains("lint"))
        dependsOn(sharedHelperApk)
}

dependencies {
    implementation(project(":hayulcore"))
    compileOnly("androidx.activity:activity:1.10.1")
    // Provided by StarLight at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    compileOnly("androidx.core:core-ktx:1.16.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // The JVM stands in for the device: SQLite through JDBC, and BouncyCastle to cross-check the key derivation.
    testImplementation("org.xerial:sqlite-jdbc:3.45.3.0")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
