import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * The setup tool keeps the patch signing key in its own data, so it must stay updatable in place: an APK signed with
 * another key can only replace it by uninstalling it first, which deletes that key (and with it the way to patch
 * KakaoTalk updates). Release builds are therefore signed with one fixed key, taken from Gradle properties, e.g. in
 * ~/.gradle/gradle.properties:
 *
 *   kakaodb.setup.storeFile=C:/keys/kakaodb-setup.jks
 *   kakaodb.setup.storePassword=...
 *   kakaodb.setup.keyAlias=setup
 *   kakaodb.setup.keyPassword=...
 *
 * (A relative storeFile is resolved against the project root.) Without them the debug key is used, with a warning.
 * Never commit that keystore: whoever holds it can ship an update that reads the patch key out of the tool's data.
 */
val setupSigning: Map<String, String?> = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .associateWith { providers.gradleProperty("kakaodb.setup.$it").orNull }
val hasSetupSigning = setupSigning.values.none { it.isNullOrEmpty() }

android {
    namespace = "dev.mooner.kakaodb.setup"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.mooner.kakaodb.setup"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
        // The helper build it carries, as the helper and the plugin report it.
        buildConfigField("String", "SHARED_HELPER_BUILD", "\"${rootProject.extra["sharedHelperBuild"]}\"")
    }
    buildFeatures {
        buildConfig = true
    }
    signingConfigs {
        if (hasSetupSigning) {
            create("setup") {
                storeFile = rootProject.file(setupSigning.getValue("storeFile")!!)
                storePassword = setupSigning["storePassword"]
                keyAlias = setupSigning["keyAlias"]
                keyPassword = setupSigning["keyPassword"]
            }
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.getByName(if (hasSetupSigning) "setup" else "debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

if (!hasSetupSigning) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name == "packageRelease" })
            logger.warn("w: :setup is signed with this machine's debug key: set kakaodb.setup.storeFile, storePassword, " +
                "keyAlias and keyPassword (see setup/build.gradle.kts) so the installed setup tool, which holds the " +
                "patch signing key, can be updated in place.")
    }
}

dependencies { implementation(project(":hayulcore")) }

val helperAssets = layout.buildDirectory.dir("generated/sharedd-assets")
val copySharedHelper = tasks.register<Copy>("copySharedHelper") {
    dependsOn(":sharedd:assembleRelease")
    from(project(":sharedd").layout.buildDirectory.file("outputs/apk/release/sharedd-release.apk"))
    into(helperAssets)
    rename { "sharedd.apk" }
}
android.sourceSets.getByName("main").assets.srcDir(helperAssets)
tasks.configureEach {
    if ((name.startsWith("merge") && name.contains("Assets")) || name.lowercase().contains("lint"))
        dependsOn(copySharedHelper)
}
